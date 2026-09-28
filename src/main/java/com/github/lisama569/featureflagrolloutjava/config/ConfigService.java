package com.github.lisama569.featureflagrolloutjava.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 配置中心：多来源优先级合并、热更新、变更审计与回滚。
 *
 * <p>一致性保证：</p>
 * <ul>
 *   <li>生效快照通过 {@link AtomicReference} 整体原子切换，读取方不会观察到半新半旧状态；</li>
 *   <li>写操作（refresh/submitChange/submitBatch/rollback）由同一把写锁串行化；</li>
 *   <li>批次提交在同一次持锁内完成全部校验与整批切换，要么整批生效要么整批原样；</li>
 *   <li>非法配置项被拒绝并保留已生效取值，不会静默覆盖；</li>
 *   <li>来源不可用时降级到该来源最近一次可用快照，而不是直接失败。</li>
 * </ul>
 */
public class ConfigService {
    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    /** 历史可回滚版本默认最多保留的个数（最旧的自动淘汰），有界以避免历史无限增长。 */
    public static final int DEFAULT_HISTORY_LIMIT = 1000;

    /** 按优先级升序排列，合并时高优先级后写覆盖低优先级。 */
    private final List<ConfigSource> sources;
    private final ConfigValidator validator;
    private final AuditLog auditLog;
    private final int historyLimit;

    private final AtomicReference<ConfigSnapshot> current;
    private final Map<Long, ConfigSnapshot> history = new ConcurrentHashMap<>();
    private final Deque<Long> historyOrder = new ArrayDeque<>();
    /** 各来源最近一次成功加载的原始配置，用于来源不可用时降级。 */
    private final Map<String, Map<String, String>> lastGoodBySource = new ConcurrentHashMap<>();
    /** 各来源最近一次成功合并时的版本，用于审计解释。 */
    private final Map<String, Long> sourceVersions = new ConcurrentHashMap<>();
    /** 各来源最近一次成功加载时间（毫秒）。 */
    private final Map<String, Long> lastSuccessAt = new ConcurrentHashMap<>();
    /** 各来源最近一次尝试加载时间（毫秒）。 */
    private final Map<String, Long> lastAttemptAt = new ConcurrentHashMap<>();
    /** 各来源最近一次失败信息。 */
    private final Map<String, String> lastErrorBySource = new ConcurrentHashMap<>();
    /** 已应用的 changeId，用于重复提交去重（幂等）。 */
    private final Set<String> appliedChangeIds = ConcurrentHashMap.newKeySet();
    private final ReentrantLock writeLock = new ReentrantLock();

    public ConfigService(List<ConfigSource> sources, ConfigValidator validator, AuditLog auditLog) {
        this(sources, validator, auditLog, DEFAULT_HISTORY_LIMIT);
    }

    public ConfigService(List<ConfigSource> sources, ConfigValidator validator, AuditLog auditLog,
                         int historyLimit) {
        this.sources = sources.stream()
                .sorted(Comparator.comparingInt(ConfigSource::priority))
                .toList();
        this.validator = validator;
        this.auditLog = auditLog;
        this.historyLimit = historyLimit;
        this.current = new AtomicReference<>(
                new ConfigSnapshot(0, Map.of(), Map.of(), System.currentTimeMillis()));
        refresh();
    }

    /**
     * 从所有来源拉取并按优先级合并。
     * 来源不可用时降级到该来源最近一次可用快照；非法项被拒绝且不覆盖已生效取值。
     */
    public ConfigSnapshot refresh() {
        writeLock.lock();
        try {
            ConfigSnapshot before = current.get();
            Map<String, String> merged = new LinkedHashMap<>();
            List<String> degraded = new ArrayList<>();
            List<ValidationError> rejected = new ArrayList<>();

            for (ConfigSource source : sources) {
                long now = System.currentTimeMillis();
                lastAttemptAt.put(source.name(), now);
                Map<String, String> raw;
                try {
                    raw = source.load();
                    lastGoodBySource.put(source.name(), Map.copyOf(raw));
                    lastSuccessAt.put(source.name(), now);
                    lastErrorBySource.remove(source.name());
                } catch (ConfigSourceUnavailableException e) {
                    Map<String, String> fallback = lastGoodBySource.get(source.name());
                    lastErrorBySource.put(source.name(), e.getMessage());
                    if (fallback == null) {
                        log.warn("[config-refresh] 来源={} 不可用且无历史快照，跳过该来源", source.name());
                        degraded.add(source.name() + "(no-snapshot)");
                        continue;
                    }
                    log.warn("[config-refresh] 来源={} 不可用，降级到最近一次可用快照（{} 项）",
                            source.name(), fallback.size());
                    degraded.add(source.name());
                    raw = fallback;
                }
                List<ValidationError> errors = validator.validate(raw);
                for (ValidationError err : errors) {
                    rejected.add(err);
                    log.warn("[config-refresh] 拒绝非法配置项: key={} value={} 原因={} 来源={}",
                            err.key(), err.value(), err.code(), source.name());
                }
                for (Map.Entry<String, String> e : raw.entrySet()) {
                    boolean invalid = errors.stream().anyMatch(err -> err.key() != null && err.key().equals(e.getKey()));
                    if (!invalid) {
                        merged.put(e.getKey(), e.getValue());
                    }
                }
                sourceVersions.put(source.name(), before.version() + 1);
            }

            ConfigSnapshot after = new ConfigSnapshot(before.version() + 1, merged,
                    new HashMap<>(sourceVersions), System.currentTimeMillis());
            current.set(after);
            remember(after);
            log.info("[config-refresh] 版本 {} -> {}，生效项={}，降级来源={}，拒绝非法项={}",
                    before.version(), after.version(), merged.size(), degraded, rejected.size());
            return after;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 提交单项变更（兼容旧调用方）。过期、重复 changeId、含非法项时
     * 抛出 {@link ChangeRejectedException}，当前生效值保持不变。
     */
    public ConfigSnapshot submitChange(ChangeRequest request) throws ChangeRejectedException {
        return submitBatch(BatchChangeRequest.from(request));
    }

    /**
     * 批次原子提交：一次提交多个开关/配置项，整批生效或整批保持原样，
     * 只形成一个可回滚版本。过期、整批重复 changeId、任一必要项非法时整批拒绝。
     */
    public ConfigSnapshot submitBatch(BatchChangeRequest request) throws ChangeRejectedException {
        writeLock.lock();
        try {
            ConfigSnapshot before = current.get();
            if (appliedChangeIds.contains(request.changeId())) {
                auditLog.append(request.changeId(), request.source(), request.operator(),
                        before.version(), before.version(),
                        AuditRecord.Action.REJECTED, RejectionReason.DUPLICATE_CHANGE_ID.name());
                throw new ChangeRejectedException(RejectionReason.DUPLICATE_CHANGE_ID,
                        "重复的变更提交: changeId=" + request.changeId());
            }
            if (request.baseVersion() != before.version()) {
                auditLog.append(request.changeId(), request.source(), request.operator(),
                        before.version(), before.version(),
                        AuditRecord.Action.REJECTED, RejectionReason.STALE_BASE_VERSION.name());
                throw new ChangeRejectedException(RejectionReason.STALE_BASE_VERSION,
                        "过期的变更: baseVersion=" + request.baseVersion()
                                + " 当前版本=" + before.version());
            }
            if (request.entries() == null || request.entries().isEmpty()) {
                auditLog.append(request.changeId(), request.source(), request.operator(),
                        before.version(), before.version(),
                        AuditRecord.Action.REJECTED, RejectionReason.INVALID_ENTRIES.name());
                throw new ChangeRejectedException(RejectionReason.INVALID_ENTRIES,
                        "变更不能为空: changeId=" + request.changeId());
            }
            List<ValidationError> errors = validator.validate(request.entries());
            if (!errors.isEmpty()) {
                auditLog.append(request.changeId(), request.source(), request.operator(),
                        before.version(), before.version(),
                        AuditRecord.Action.REJECTED, RejectionReason.INVALID_ENTRIES.name());
                throw new ChangeRejectedException(RejectionReason.INVALID_ENTRIES,
                        "变更包含非法配置项: " + errors, errors);
            }

            // 整批合并在同一次持锁内完成，并通过单一原子引用整体切换
            Map<String, String> merged = new LinkedHashMap<>(before.entries());
            merged.putAll(request.entries());
            ConfigSnapshot after = new ConfigSnapshot(before.version() + 1, merged,
                    new HashMap<>(sourceVersions), System.currentTimeMillis());
            current.set(after);
            remember(after);
            appliedChangeIds.add(request.changeId());
            auditLog.append(request.changeId(), request.source(), request.operator(),
                    before.version(), after.version(), AuditRecord.Action.APPLIED,
                    "batchSize=" + request.entries().size()
                            + " keys=" + request.entries().keySet()
                            + " entries=" + request.entries());
            log.info("[config-change] changeId={} 来源={} 操作者={} 版本 {} -> {}，整批键数={}",
                    request.changeId(), request.source(), request.operator(),
                    before.version(), after.version(), request.entries().size());
            return after;
        } finally {
            writeLock.unlock();
        }
    }

    /** 回滚到指定历史版本；目标版本不存在时抛出 {@link IllegalArgumentException}。 */
    public ConfigSnapshot rollback(long toVersion, String operator) {
        writeLock.lock();
        try {
            ConfigSnapshot before = current.get();
            ConfigSnapshot target = history.get(toVersion);
            if (target == null) {
                throw new IllegalArgumentException("回滚目标版本不存在或已超出历史保留上限: " + toVersion);
            }
            ConfigSnapshot after = new ConfigSnapshot(before.version() + 1, target.entries(),
                    target.sourceVersions(), System.currentTimeMillis());
            current.set(after);
            remember(after);
            auditLog.append("rollback-to-" + toVersion, operator, operator,
                    before.version(), after.version(), AuditRecord.Action.ROLLED_BACK,
                    "回滚到版本 " + toVersion + "（整批恢复该版本全部内容）");
            log.info("[config-rollback] 操作人={} 版本 {} -> {}（内容取自版本 {}）",
                    operator, before.version(), after.version(), toVersion);
            return after;
        } finally {
            writeLock.unlock();
        }
    }

    private void remember(ConfigSnapshot snapshot) {
        history.put(snapshot.version(), snapshot);
        historyOrder.addLast(snapshot.version());
        while (historyOrder.size() > historyLimit) {
            Long oldest = historyOrder.removeFirst();
            history.remove(oldest);
        }
    }

    public String get(String key) {
        return current.get().get(key);
    }

    public ConfigSnapshot currentSnapshot() {
        return current.get();
    }

    public Map<Long, ConfigSnapshot> history() {
        return Map.copyOf(history);
    }

    public AuditLog auditLog() {
        return auditLog;
    }

    public ConfigValidator validator() {
        return validator;
    }

    /** 只读判断 changeId 是否已经真实发布过（试算不写入该集合）。 */
    public boolean isAppliedChangeId(String changeId) {
        return appliedChangeIds.contains(changeId);
    }

    /** 已按配置顺序排好的来源视图（优先级升序）。 */
    public List<ConfigSource> sources() {
        return sources;
    }

    /** 各来源当前健康/降级状态。即使某来源不可用，该查询本身也不依赖该来源，始终可用。 */
    public List<SourceStatus> sourceStatuses() {
        List<SourceStatus> result = new ArrayList<>();
        for (ConfigSource source : sources) {
            String name = source.name();
            Long attempt = lastAttemptAt.get(name);
            Long success = lastSuccessAt.get(name);
            Map<String, String> lastGood = lastGoodBySource.get(name);
            String error = lastErrorBySource.get(name);
            String state;
            boolean degraded;
            if (error == null) {
                state = attempt == null ? SourceStatus.STATE_NEVER_LOADED : SourceStatus.STATE_OK;
                degraded = false;
            } else if (lastGood != null) {
                state = SourceStatus.STATE_DEGRADED;
                degraded = true;
            } else {
                state = SourceStatus.STATE_NO_SNAPSHOT;
                degraded = true;
            }
            result.add(new SourceStatus(name, source.priority(), state, degraded,
                    attempt == null ? -1 : attempt,
                    success == null ? -1 : success,
                    lastGood == null ? 0 : lastGood.size(),
                    error));
        }
        return List.copyOf(result);
    }
}
