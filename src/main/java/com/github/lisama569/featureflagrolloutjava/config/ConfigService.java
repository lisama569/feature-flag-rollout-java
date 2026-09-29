package com.github.lisama569.featureflagrolloutjava.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 *   <li>写操作（refresh/submitChange/rollback）由同一把写锁串行化；</li>
 *   <li>非法配置项被拒绝并保留已生效取值，不会静默覆盖；</li>
 *   <li>来源不可用时降级到该来源最近一次可用快照，而不是直接失败。</li>
 * </ul>
 */
public class ConfigService {
    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    /** 版本历史默认保留上限（含当前版本），超出按 FIFO 淘汰最老版本，避免内存只增不减。 */
    public static final int DEFAULT_HISTORY_LIMIT = 500;
    /** 在总览/查询中额外展示的“最近被淘汰版本号”条数（仅用于提示，不存快照内容）。 */
    public static final int RECENT_EVICTED_TRACK = 20;

    /** 按优先级升序排列，合并时高优先级后写覆盖低优先级。 */
    private final List<ConfigSource> sources;
    private final ConfigValidator validator;
    private final AuditLog auditLog;
    /** 可回滚历史版本的保留上限（含当前版本）。 */
    private final int historyLimit;

    private final AtomicReference<ConfigSnapshot> current;
    /** 保留中的历史快照：version -> 快照（数量受 historyLimit 约束）。 */
    private final Map<Long, ConfigSnapshot> history = new ConcurrentHashMap<>();
    /** 保留中的版本号队列（升序），用于按 FIFO 淘汰最老版本。 */
    private final java.util.Deque<Long> retainedVersions = new java.util.ArrayDeque<>();
    /** 曾经产生过的最大版本号，用于区分“从未存在”与“已被淘汰”。 */
    private long highestVersionEver = 0;
    /** 因超过保留上限被淘汰的版本总数。 */
    private int evictedVersionCount = 0;
    /** 最近被淘汰的版本号（升序，仅提示用，最多 RECENT_EVICTED_TRACK 个）。 */
    private final java.util.Deque<Long> recentlyEvicted = new java.util.ArrayDeque<>();
    /** 各来源最近一次成功加载的原始配置，用于来源不可用时降级。 */
    private final Map<String, Map<String, String>> lastGoodBySource = new ConcurrentHashMap<>();
    /** 各来源最近一次成功加载对应的快照版本，用于审计解释。 */
    private final Map<String, Long> sourceVersions = new ConcurrentHashMap<>();
    /** 已应用的 changeId，用于重复提交去重（幂等）。 */
    private final Set<String> appliedChangeIds = ConcurrentHashMap.newKeySet();
    /** 各来源最近一次拉取健康状况，供值班总览展示降级状态。 */
    private final Map<String, SourceHealth> healthBySource = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();

    public ConfigService(List<ConfigSource> sources, ConfigValidator validator, AuditLog auditLog) {
        this(sources, validator, auditLog, DEFAULT_HISTORY_LIMIT);
    }

    public ConfigService(List<ConfigSource> sources, ConfigValidator validator, AuditLog auditLog,
                         int historyLimit) {
        if (historyLimit < 1) {
            throw new IllegalArgumentException("历史保留上限必须 >= 1: " + historyLimit);
        }
        this.sources = sources.stream()
                .sorted(Comparator.comparingInt(ConfigSource::priority))
                .toList();
        this.validator = validator;
        this.auditLog = auditLog;
        this.historyLimit = historyLimit;
        for (ConfigSource source : this.sources) {
            healthBySource.put(source.name(), new SourceHealth(
                    source.name(), source.priority(), false, null, null, null, 0));
        }
        this.current = new AtomicReference<>(
                new ConfigSnapshot(0, Map.of(), Map.of(), System.currentTimeMillis()));
        // 初始空版本也纳入有界历史（可回滚到“初始空配置”），统一走保留上限与 FIFO 淘汰
        recordHistory(current.get());
        refresh();
    }

    /**
     * 把新快照记入历史并按 FIFO 淘汰超出保留上限的最老版本。
     * 必须在持有 {@link #writeLock} 时调用。
     */
    private void recordHistory(ConfigSnapshot snapshot) {
        long version = snapshot.version();
        history.put(version, snapshot);
        retainedVersions.addLast(version);
        if (version > highestVersionEver) {
            highestVersionEver = version;
        }
        while (retainedVersions.size() > historyLimit) {
            Long evicted = retainedVersions.pollFirst();
            if (evicted != null) {
                history.remove(evicted);
                evictedVersionCount++;
                recentlyEvicted.addLast(evicted);
                while (recentlyEvicted.size() > RECENT_EVICTED_TRACK) {
                    recentlyEvicted.pollFirst();
                }
                log.info("[config-history] 版本 v{} 超过保留上限 {} 被淘汰（累计淘汰 {}），该版本不再可回滚",
                        evicted, historyLimit, evictedVersionCount);
            }
        }
    }

    /** 历史保留状况（在总览/查询里展示淘汰提示）。 */
    public HistoryRetentionStatus historyRetentionStatus() {
        writeLock.lock();
        try {
            List<Long> retained = List.copyOf(retainedVersions);
            return new HistoryRetentionStatus(historyLimit, retained.size(), retained,
                    retainedVersions.peekFirst(), evictedVersionCount,
                    List.copyOf(recentlyEvicted));
        } finally {
            writeLock.unlock();
        }
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
                Map<String, String> raw;
                SourceHealth prev = healthBySource.getOrDefault(source.name(),
                        new SourceHealth(source.name(), source.priority(), false, null, null, null, 0));
                try {
                    raw = source.load();
                    lastGoodBySource.put(source.name(), Map.copyOf(raw));
                    // 成功：清除降级标记，保留最近一次失败信息以便排查
                    healthBySource.put(source.name(), new SourceHealth(
                            source.name(), source.priority(), false,
                            System.currentTimeMillis(),
                            prev.lastFailureMillis(), prev.lastFailureReason(), raw.size()));
                } catch (ConfigSourceUnavailableException e) {
                    Map<String, String> fallback = lastGoodBySource.get(source.name());
                    int fallbackCount = fallback == null ? 0 : fallback.size();
                    healthBySource.put(source.name(), new SourceHealth(
                            source.name(), source.priority(), true,
                            prev.lastSuccessMillis(), System.currentTimeMillis(),
                            e.getMessage(), fallbackCount));
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
            recordHistory(after);
            log.info("[config-refresh] 版本 {} -> {}，生效项={}，降级来源={}，拒绝非法项={}",
                    before.version(), after.version(), merged.size(), degraded, rejected.size());
            return after;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 提交变更。过期（baseVersion 与当前版本不一致）、重复 changeId、含非法项时
     * 抛出 {@link ChangeRejectedException}，当前生效值保持不变。
     */
    public ConfigSnapshot submitChange(ChangeRequest request) throws ChangeRejectedException {
        writeLock.lock();
        try {
            ConfigSnapshot before = current.get();
            if (appliedChangeIds.contains(request.changeId())) {
                auditLog.append(request.changeId(), request.source(), before.version(), before.version(),
                        AuditRecord.Action.REJECTED, RejectionReason.DUPLICATE_CHANGE_ID.name());
                throw new ChangeRejectedException(RejectionReason.DUPLICATE_CHANGE_ID,
                        "重复的变更提交: changeId=" + request.changeId());
            }
            if (request.baseVersion() != before.version()) {
                auditLog.append(request.changeId(), request.source(), before.version(), before.version(),
                        AuditRecord.Action.REJECTED, RejectionReason.STALE_BASE_VERSION.name());
                throw new ChangeRejectedException(RejectionReason.STALE_BASE_VERSION,
                        "过期的变更: baseVersion=" + request.baseVersion()
                                + " 当前版本=" + before.version());
            }
            List<ValidationError> errors = validator.validate(request.entries());
            if (!errors.isEmpty()) {
                auditLog.append(request.changeId(), request.source(), before.version(), before.version(),
                        AuditRecord.Action.REJECTED, RejectionReason.INVALID_ENTRIES.name());
                throw new ChangeRejectedException(RejectionReason.INVALID_ENTRIES,
                        "变更包含非法配置项: " + errors, errors);
            }

            Map<String, String> merged = new LinkedHashMap<>(before.entries());
            merged.putAll(request.entries());
            ConfigSnapshot after = new ConfigSnapshot(before.version() + 1, merged,
                    new HashMap<>(sourceVersions), System.currentTimeMillis());
            current.set(after);
            recordHistory(after);
            appliedChangeIds.add(request.changeId());
            List<String> changedKeys = List.copyOf(request.entries().keySet());
            // 审计里带上每个键发布后的完整值，事后复盘不必再猜
            Map<String, String> publishedValues = new LinkedHashMap<>();
            for (String key : changedKeys) {
                publishedValues.put(key, after.get(key));
            }
            auditLog.append(request.changeId(), request.source(), request.source(),
                    before.version(), after.version(), AuditRecord.Action.APPLIED,
                    "keys=" + changedKeys, changedKeys, publishedValues);
            log.info("[config-change] changeId={} 来源={} 版本 {} -> {}，变更键={}",
                    request.changeId(), request.source(), before.version(), after.version(),
                    changedKeys);
            return after;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 回滚到指定历史版本；版本号继续递增（回滚本身也是一次可审计变更，记 ROLLED_BACK）。
     *
     * <p>目标版本从未存在时抛 {@link VersionNotFoundException}；
     * 目标版本曾存在但已因超过历史保留上限被淘汰时抛 {@link VersionEvictedException}
     * （明确告知不可用并给出仍可回滚的最早版本，绝不静默恢复成别的内容）。</p>
     */
    public ConfigSnapshot rollback(long toVersion, String operator) {
        writeLock.lock();
        try {
            ConfigSnapshot before = current.get();
            ConfigSnapshot target = history.get(toVersion);
            if (target == null) {
                if (toVersion <= highestVersionEver) {
                    long oldest = retainedVersions.isEmpty() ? before.version() : retainedVersions.peekFirst();
                    throw new VersionEvictedException(toVersion, oldest, historyLimit);
                }
                throw new VersionNotFoundException(toVersion, before.version());
            }
            ConfigSnapshot after = new ConfigSnapshot(before.version() + 1, target.entries(),
                    target.sourceVersions(), System.currentTimeMillis());
            current.set(after);
            recordHistory(after);
            // 回滚审计同样带上恢复后的完整键值：整批回滚时恢复的是整批内容，复盘可逐条核对
            auditLog.append("rollback-to-" + toVersion, operator, operator,
                    before.version(), after.version(), AuditRecord.Action.ROLLED_BACK,
                    "回滚到版本 " + toVersion,
                    List.copyOf(target.entries().keySet()), target.entries());
            log.info("[config-rollback] 操作人={} 版本 {} -> {}（内容取自版本 {}，恢复键数={}）",
                    operator, before.version(), after.version(), toVersion, target.entries().size());
            return after;
        } finally {
            writeLock.unlock();
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

    /**
     * 发布前试算：基于当前生效版本计算“假如现在提交”的合并预计结果。
     *
     * <p>边界保证：</p>
     * <ul>
     *   <li>只读当前快照，绝不修改生效配置、可回滚历史；</li>
     *   <li>不写入 {@code appliedChangeIds}，不占用真实发布的幂等位置，也不写审计；</li>
     *   <li>成功（wouldApply=true）时返回按当前版本合并后的<b>完整预计配置</b>
     *       {@code projectedEntries}，并逐项给出与当前值的对照（diffs/affectedFlags）；</li>
     *   <li>基准版本过期 / 内容非法 / 标识已真实发布过，分别返回可区分的拒绝原因，
     *       {@code wouldApply=false}，{@code proposedVersion=null}，
     *       {@code projectedEntries=null}，diffs/affectedFlags 为空列表——
     *       注定不会生效的新开关值/新比例不会当作预计值或受影响项返回，调用方不能误以为已生效。</li>
     * </ul>
     * 拒绝原因判定顺序与 {@link #submitChange} 保持一致：重复标识优先于过期基准。
     */
    public PreviewResult previewChange(ChangeRequest request) {
        writeLock.lock();
        try {
            ConfigSnapshot before = current.get();

            RejectionReason reason;
            String message;
            List<ValidationError> errors = List.of();

            if (appliedChangeIds.contains(request.changeId())) {
                reason = RejectionReason.DUPLICATE_CHANGE_ID;
                message = "试算结果: 该标识此前已真实发布过，再次提交会被拒绝（幂等），当前配置不会变化。changeId="
                        + request.changeId();
            } else if (request.baseVersion() != before.version()) {
                reason = RejectionReason.STALE_BASE_VERSION;
                message = "试算结果: 基准版本已过期，正式提交会被拒绝。baseVersion=" + request.baseVersion()
                        + " 当前版本=" + before.version();
            } else {
                errors = validator.validate(request.entries());
                if (!errors.isEmpty()) {
                    reason = RejectionReason.INVALID_ENTRIES;
                    message = "试算结果: 含 " + errors.size() + " 个非法配置项，正式提交会被整批拒绝；"
                            + "下列预计值不会生效，故不在此回带。";
                } else {
                    reason = null;
                    message = "试算结果: 校验通过，正式提交预计从版本 " + before.version()
                            + " 升到 " + (before.version() + 1) + "，当前配置未被修改。";
                }
            }

            boolean wouldApply = reason == null;
            Long proposedVersion = wouldApply ? before.version() + 1 : null;
            // 当前完整生效配置：成功/被拒都返回，供对照
            Map<String, String> currentEntries = before.entries();
            // 只有可接受时才计算合并结果与差异；被拒绝时不回带任何注定不生效的值
            Map<String, String> projectedEntries;
            List<EntryDiff> diffs;
            List<FlagImpact> impacts;
            if (wouldApply) {
                Map<String, String> projected = new LinkedHashMap<>(before.entries());
                projected.putAll(request.entries());
                projectedEntries = Map.copyOf(projected);
                diffs = buildDiffs(before.entries(), request.entries());
                impacts = buildFlagImpacts(before.entries(), request.entries());
            } else {
                projectedEntries = null;
                diffs = List.of();
                impacts = List.of();
            }

            PreviewResult result = new PreviewResult(wouldApply, reason, message,
                    request.baseVersion(), before.version(), proposedVersion,
                    errors, diffs, impacts, currentEntries, projectedEntries);
            log.info("[config-preview] changeId={} | 依据: 当前版本={} baseVersion={} 拒绝原因={} 非法项={} 受影响开关={} | 结果: wouldApply={} 预计版本={} 完整预计项={}",
                    request.changeId(), before.version(), request.baseVersion(), reason,
                    errors.size(), impacts.stream().map(FlagImpact::flag).toList(),
                    wouldApply, proposedVersion,
                    projectedEntries == null ? null : projectedEntries.size());
            return result;
        } finally {
            writeLock.unlock();
        }
    }

    /** 逐项构造 当前值 vs 试算合并值 的差异（仅覆盖本次提交涉及的键）。 */
    private List<EntryDiff> buildDiffs(Map<String, String> currentEntries, Map<String, String> proposed) {
        List<EntryDiff> diffs = new ArrayList<>();
        for (Map.Entry<String, String> e : proposed.entrySet()) {
            String key = e.getKey();
            String cur = currentEntries.get(key);
            String next = e.getValue();
            String changeType;
            if (cur == null) {
                changeType = "ADDED";
            } else if (java.util.Objects.equals(cur, next)) {
                changeType = "UNCHANGED";
            } else {
                changeType = "UPDATED";
            }
            diffs.add(new EntryDiff(key, cur, next, changeType));
        }
        return List.copyOf(diffs);
    }

    /** 计算本次提交涉及的 feature 开关在总开关与灰度比例上的前后变化。 */
    private List<FlagImpact> buildFlagImpacts(Map<String, String> currentEntries, Map<String, String> proposed) {
        Set<String> flags = new LinkedHashSet<>();
        for (String key : proposed.keySet()) {
            String flag = flagOf(key);
            if (flag != null) {
                flags.add(flag);
            }
        }
        Map<String, String> projected = new LinkedHashMap<>(currentEntries);
        projected.putAll(proposed);

        List<FlagImpact> impacts = new ArrayList<>();
        for (String flag : flags) {
            Boolean enBefore = boolOrNull(currentEntries.get("feature." + flag + ".enabled"));
            Integer pctBefore = intOrNull(currentEntries.get("feature." + flag + ".percentage"));
            Boolean enAfter = boolOrNull(projected.get("feature." + flag + ".enabled"));
            Integer pctAfter = intOrNull(projected.get("feature." + flag + ".percentage"));
            boolean existed = currentEntries.containsKey("feature." + flag + ".enabled")
                    || currentEntries.containsKey("feature." + flag + ".percentage");
            boolean changed = !java.util.Objects.equals(enBefore, enAfter)
                    || !java.util.Objects.equals(pctBefore, pctAfter);
            String changeType = !existed ? "ADDED" : changed ? "UPDATED" : "UNCHANGED";
            impacts.add(new FlagImpact(flag, enBefore, pctBefore, enAfter, pctAfter, changeType));
        }
        return List.copyOf(impacts);
    }

    /** 从配置键中解析开关名：feature.<name>.enabled / feature.<name>.percentage，否则 null。 */
    public static String flagOf(String key) {
        if (key == null || !key.startsWith("feature.")) {
            return null;
        }
        if (key.endsWith(".enabled")) {
            return key.substring("feature.".length(), key.length() - ".enabled".length());
        }
        if (key.endsWith(".percentage")) {
            return key.substring("feature.".length(), key.length() - ".percentage".length());
        }
        return null;
    }

    private static Boolean boolOrNull(String raw) {
        return raw == null ? null : Boolean.parseBoolean(raw.trim());
    }

    private static Integer intOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 批次原子发布：把多个开关的配置项作为一个整体提交。
     *
     * <p>原子性保证：任一必要项非法、基准版本过期、批次标识重复或批次为空时，整批拒绝，
     * 不会出现任何一部分先生效；判定方在整个过程中只会看到完整的旧快照或完整的新快照
     * （生效配置仍通过 {@link AtomicReference} 整体切换，写操作由同一把写锁串行化）。
     * 成功发布只形成一个可回滚版本，审计记录整批内容、来源、操作者与前后版本。
     * batchId 与单条 changeId 共用同一幂等空间。</p>
     */
    public ConfigSnapshot publishBatch(BatchPublishRequest request) throws ChangeRejectedException {
        writeLock.lock();
        try {
            ConfigSnapshot before = current.get();

            // 1) 先把整批展平为一个键集合；空批次直接拒绝，绝不部分生效。
            Map<String, String> batchEntries = new LinkedHashMap<>();
            if (request.items() != null) {
                for (BatchPublishRequest.BatchItem item : request.items()) {
                    if (item == null || item.flag() == null || item.flag().isBlank()
                            || item.entries() == null) {
                        auditLog.append(request.batchId(), request.source(), request.operator(),
                                before.version(), before.version(), AuditRecord.Action.REJECTED,
                                RejectionReason.INVALID_ENTRIES.name() + ": 批次项缺少 flag 或 entries",
                                List.of());
                        throw new ChangeRejectedException(RejectionReason.INVALID_ENTRIES,
                                "批次包含非法条目（缺少 flag 或 entries）");
                    }
                    batchEntries.putAll(item.entries());
                }
            }

            // 2) 幂等（与单条提交同一去重空间）
            if (appliedChangeIds.contains(request.batchId())) {
                auditLog.append(request.batchId(), request.source(), request.operator(),
                        before.version(), before.version(), AuditRecord.Action.REJECTED,
                        RejectionReason.DUPLICATE_CHANGE_ID.name(), List.of());
                throw new ChangeRejectedException(RejectionReason.DUPLICATE_CHANGE_ID,
                        "重复的批次提交: batchId=" + request.batchId());
            }

            // 3) 空批次
            if (batchEntries.isEmpty()) {
                auditLog.append(request.batchId(), request.source(), request.operator(),
                        before.version(), before.version(), AuditRecord.Action.REJECTED,
                        RejectionReason.EMPTY_BATCH.name(), List.of());
                throw new ChangeRejectedException(RejectionReason.EMPTY_BATCH,
                        "批次为空，没有任何可发布配置项: batchId=" + request.batchId());
            }

            // 4) 乐观并发基准
            if (request.baseVersion() != before.version()) {
                auditLog.append(request.batchId(), request.source(), request.operator(),
                        before.version(), before.version(), AuditRecord.Action.REJECTED,
                        RejectionReason.STALE_BASE_VERSION.name(), List.of());
                throw new ChangeRejectedException(RejectionReason.STALE_BASE_VERSION,
                        "批次基准版本过期: baseVersion=" + request.baseVersion()
                                + " 当前版本=" + before.version());
            }

            // 5) 整批校验：有任何非法项，整批拒绝
            List<ValidationError> errors = validator.validate(batchEntries);
            if (!errors.isEmpty()) {
                auditLog.append(request.batchId(), request.source(), request.operator(),
                        before.version(), before.version(), AuditRecord.Action.REJECTED,
                        RejectionReason.INVALID_ENTRIES.name(), List.copyOf(batchEntries.keySet()));
                throw new ChangeRejectedException(RejectionReason.INVALID_ENTRIES,
                        "批次包含非法配置项，整批不生效: " + errors, errors);
            }

            // 6) 全部检查通过后，一次性合并并整体切换：只产生一个新版本
            Map<String, String> merged = new LinkedHashMap<>(before.entries());
            merged.putAll(batchEntries);
            ConfigSnapshot after = new ConfigSnapshot(before.version() + 1, merged,
                    new HashMap<>(sourceVersions), System.currentTimeMillis());
            current.set(after);
            recordHistory(after);
            appliedChangeIds.add(request.batchId());
            List<String> affectedKeys = List.copyOf(batchEntries.keySet());
            // 一条批次审计记录里完整保存整批键的最终发布值，事后复盘/追责不必再猜
            Map<String, String> publishedValues = new LinkedHashMap<>();
            for (String key : affectedKeys) {
                publishedValues.put(key, after.get(key));
            }
            auditLog.append(request.batchId(), request.source(), request.operator(),
                    before.version(), after.version(), AuditRecord.Action.APPLIED,
                    "BATCH items=" + request.items().size() + " keys=" + affectedKeys,
                    affectedKeys, publishedValues);
            log.info("[config-batch] batchId={} 来源={} 操作者={} 版本 {} -> {}，项数={} 整批键={}",
                    request.batchId(), request.source(), request.operator(),
                    before.version(), after.version(), request.items().size(), affectedKeys);
            return after;
        } finally {
            writeLock.unlock();
        }
    }

    /** 各来源最近一次拉取健康状况（按优先级升序，供总览展示降级与最近可用情况）。 */
    public List<SourceHealth> sourceHealth() {
        List<SourceHealth> result = new ArrayList<>();
        for (ConfigSource source : sources) {
            SourceHealth h = healthBySource.get(source.name());
            if (h != null) {
                result.add(h);
            }
        }
        return List.copyOf(result);
    }
}
