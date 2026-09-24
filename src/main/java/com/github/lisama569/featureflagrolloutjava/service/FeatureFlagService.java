package com.github.lisama569.featureflagrolloutjava.service;

import com.github.lisama569.featureflagrolloutjava.audit.AuditLog;
import com.github.lisama569.featureflagrolloutjava.audit.SnapshotHistory;
import com.github.lisama569.featureflagrolloutjava.domain.AuditKind;
import com.github.lisama569.featureflagrolloutjava.domain.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.domain.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry;
import com.github.lisama569.featureflagrolloutjava.domain.DegradedSource;
import com.github.lisama569.featureflagrolloutjava.domain.EvaluationResult;
import com.github.lisama569.featureflagrolloutjava.domain.FlagEntry;
import com.github.lisama569.featureflagrolloutjava.domain.MergedConfig;
import com.github.lisama569.featureflagrolloutjava.domain.RejectReason;
import com.github.lisama569.featureflagrolloutjava.domain.SourceSnapshot;
import com.github.lisama569.featureflagrolloutjava.domain.ValidationIssue;
import com.github.lisama569.featureflagrolloutjava.merge.ConfigMerger;
import com.github.lisama569.featureflagrolloutjava.rollout.Bucketer;
import com.github.lisama569.featureflagrolloutjava.source.ConfigSource;
import com.github.lisama569.featureflagrolloutjava.source.SourceUnavailableException;
import com.github.lisama569.featureflagrolloutjava.validation.ConfigValidator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置与特性开关核心服务。
 *
 * <h3>一致性模型</h3>
 * 当前生效配置始终是同一个不可变 {@link MergedConfig}，由 {@link AtomicReference} 持有。
 * 读取方（{@link #evaluate}/{@link #getProperty}）一次调用只取一次快照引用，在该引用上
 * 完成全部判定，因此配置热切换对单次请求是原子的：不会出现新旧分支混用、半新半旧。
 *
 * <h3>分层与拒绝</h3>
 * 重载时按来源优先级从高到低拉取并逐条校验：非法条目被剔除（带可区分原因），不参与合并，
 * 因而高优先级来源的非法值不会静默覆盖低优先级的已生效值。
 *
 * <h3>降级</h3>
 * 来源不可用时使用该来源最近一次可用快照继续参与合并，并记录 {@link DegradedSource} 与
 * DEGRADATION 审计；无历史快照时跳过该来源而不是让整体加载失败。
 *
 * <h3>并发变更</h3>
 * {@link #submit} 以乐观版本号拒绝过期变更，以 requestId 幂等键拒绝重复提交，
 * 以内容比对拒绝无变化提交；所有写操作在同一把锁上串行化，提交后原子发布新快照。
 *
 * <h3>回滚</h3>
 * {@link #rollback} 恢复目标版本当时各来源的完整快照内容，再走正常合并发布为新版本，
 * 审计中记录来源版本 from/to，回滚后定时重载不会产生冲突。
 */
@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final List<ConfigSource> sources;
    private final Map<String, ConfigSource> sourceByName;
    private final ConfigValidator validator;
    private final ConfigMerger merger;
    private final Bucketer bucketer;
    private final AuditLog auditLog;
    private final SnapshotHistory history;

    private final AtomicReference<MergedConfig> active = new AtomicReference<>();
    private final AtomicLong configVersionSeq = new AtomicLong(0);
    private final CopyOnWriteArraySet<String> processedRequestIds = new CopyOnWriteArraySet<>();

    /** 各来源最近一次可用的原始快照（降级使用）。 */
    private final Map<String, SourceSnapshot> lastGoodPerSource = new ConcurrentHashMap<>();
    /** 每个已发布配置版本当时各来源的实际状态（回滚使用）。 */
    private final Map<Long, List<SourceSnapshot>> sourceStatesAtVersion = new ConcurrentHashMap<>();

    /** 写操作锁：reload / submit / rollback 串行化。 */
    private final Object writeLock = new Object();

    public FeatureFlagService(List<ConfigSource> sources,
                              ConfigValidator validator,
                              ConfigMerger merger,
                              Bucketer bucketer,
                              AuditLog auditLog,
                              SnapshotHistory history) {
        this.sources = sources.stream()
                .sorted(Comparator.comparingInt(ConfigSource::priority).reversed())
                .toList();
        Map<String, ConfigSource> byName = new LinkedHashMap<>();
        for (ConfigSource s : this.sources) {
            if (byName.put(s.name(), s) != null) {
                throw new IllegalStateException("duplicate config source name: " + s.name());
            }
        }
        this.sourceByName = Map.copyOf(byName);
        this.validator = validator;
        this.merger = merger;
        this.bucketer = bucketer;
        this.auditLog = auditLog;
        this.history = history;
    }

    @PostConstruct
    void bootstrap() {
        reload();
    }

    /**
     * 全量重载：拉取 → 降级替换 → 校验 → 分层合并 → 原子发布。
     */
    public MergedConfig reload() {
        synchronized (writeLock) {
            List<SourceSnapshot> mergeInputs = new ArrayList<>();
            List<SourceSnapshot> sourceStates = new ArrayList<>();
            List<ValidationIssue> rejected = new ArrayList<>();
            List<DegradedSource> degraded = new ArrayList<>();

            for (ConfigSource source : sources) {
                SourceSnapshot raw;
                try {
                    raw = source.load();
                    lastGoodPerSource.put(source.name(), raw);
                } catch (SourceUnavailableException e) {
                    SourceSnapshot cached = lastGoodPerSource.get(source.name());
                    long usedVersion = cached == null ? -1L : cached.sourceVersion();
                    degraded.add(new DegradedSource(source.name(), e.getMessage(), usedVersion));
                    auditLog.append(new AuditRecord(Instant.now(), AuditKind.DEGRADATION,
                            source.name(), null, usedVersion,
                            "source unavailable; using " + (cached == null
                                    ? "no cached snapshot (source skipped)"
                                    : "cached snapshot v" + usedVersion),
                            active.get() == null ? -1L : active.get().version()));
                    log.warn("配置来源 [{}] 不可用，{}", source.name(),
                            cached == null ? "无缓存快照，跳过" : "降级使用缓存快照 v" + usedVersion);
                    if (cached == null) {
                        continue;
                    }
                    raw = cached;
                }

                ConfigValidator.Result vr = validator.validate(source.name(), raw.entries());
                rejected.addAll(vr.issues());
                SourceSnapshot effective = new SourceSnapshot(
                        source.name(), raw.sourceVersion(), vr.validEntries());
                mergeInputs.add(effective);
                sourceStates.add(raw);
            }

            MergedConfig candidate = merger.merge(
                    configVersionSeq.get() + 1, mergeInputs, rejected, degraded);

            MergedConfig current = active.get();
            boolean contentUnchanged = current != null
                    && candidate.flags().equals(current.flags())
                    && candidate.properties().equals(current.properties());
            if (contentUnchanged && rejected.isEmpty() && degraded.isEmpty()) {
                log.debug("重载结果与当前 v{} 内容一致，不发布新版本", current.version());
                return current;
            }

            long newVersion = configVersionSeq.incrementAndGet();
            MergedConfig published = new MergedConfig(newVersion,
                    candidate.flags(), candidate.properties(),
                    candidate.rejected(), candidate.degraded());
            history.retain(published);
            sourceStatesAtVersion.put(newVersion, List.copyOf(sourceStates));
            active.set(published);

            auditLog.append(new AuditRecord(Instant.now(), AuditKind.RELOAD,
                    "*", current == null ? null : current.version(), newVersion,
                    "merged " + mergeInputs.size() + " sources, rejected="
                            + rejected.size() + " entries, degraded="
                            + degraded.size() + " sources",
                    newVersion));
            log.info("配置已发布 v{}：拒绝非法条目 {} 条，降级来源 {} 个",
                    newVersion, rejected.size(), degraded.size());
            return published;
        }
    }

    /**
     * 判定开关取值。整个判定基于同一次取出的快照引用。
     */
    public EvaluationResult evaluate(String flagKey, String subject) {
        MergedConfig cfg = active.get();
        if (cfg == null) {
            throw new IllegalStateException("configuration not initialized");
        }
        FlagEntry flag = cfg.flags().get(flagKey);
        if (flag == null) {
            throw new IllegalArgumentException("unknown flag: " + flagKey);
        }
        int bucket = bucketer.bucket(flag.key(), subject, flag.variantKey());

        if (!flag.enabled()) {
            String reason = "总开关关闭(enabled=false)，所有主体取默认值 " + flag.defaultValue();
            return new EvaluationResult(flagKey, subject, flag.defaultValue(),
                    flag.defaultValue(), bucket, flag.rolloutPercentage(), cfg.version(), reason);
        }

        boolean hit = bucket < flag.rolloutPercentage();
        boolean value = hit != flag.defaultValue(); // 命中放量 → 翻转默认值
        String reason = "总开关开启(enabled=true)，放量=" + flag.rolloutPercentage()
                + "%，桶值=" + bucket + "，" + (hit
                ? bucket + " < " + flag.rolloutPercentage() + " → 命中灰度，取翻转值 " + value
                : bucket + " >= " + flag.rolloutPercentage() + " → 未命中，取默认值 " + flag.defaultValue());
        return new EvaluationResult(flagKey, subject, value,
                hit && value, bucket, flag.rolloutPercentage(), cfg.version(), reason);
    }

    /**
     * 读取普通属性生效值。
     */
    public Optional<String> getProperty(String key) {
        MergedConfig cfg = active.get();
        if (cfg == null) {
            throw new IllegalStateException("configuration not initialized");
        }
        return Optional.ofNullable(cfg.properties().get(key)).map(p -> p.value());
    }

    /**
     * 乐观并发提交变更。
     */
    public MergedConfig submit(ChangeRequest request) {
        synchronized (writeLock) {
            ConfigSource source = sourceByName.get(request.sourceName());
            if (source == null) {
                throw rejected(request, RejectReason.UNKNOWN_SOURCE,
                        "unknown config source: " + request.sourceName());
            }
            if (!processedRequestIds.add(request.requestId())) {
                throw rejected(request, RejectReason.DUPLICATE_SUBMISSION,
                        "requestId 已处理过: " + request.requestId());
            }

            SourceSnapshot base;
            try {
                base = source.load();
            } catch (SourceUnavailableException e) {
                processedRequestIds.remove(request.requestId());
                throw rejected(request, RejectReason.SOURCE_UNAVAILABLE,
                        "来源不可用，无法提交: " + e.getMessage());
            }

            if (base.sourceVersion() != request.expectedSourceVersion()) {
                processedRequestIds.remove(request.requestId());
                throw rejected(request, RejectReason.STALE_VERSION,
                        "期望来源版本 v" + request.expectedSourceVersion()
                                + "，当前已为 v" + base.sourceVersion());
            }

            List<ConfigEntry> requested = request.entries() == null ? List.of() : request.entries();
            if (base.entries().equals(requested)) {
                processedRequestIds.remove(request.requestId());
                throw rejected(request, RejectReason.NO_OP_CHANGE,
                        "提交内容与来源当前内容完全一致，无实际变更");
            }

            ConfigValidator.Result vr = validator.validate(source.name(), requested);
            if (!vr.issues().isEmpty()) {
                processedRequestIds.remove(request.requestId());
                String detail = "非法条目被拒绝: " + vr.issues();
                auditLog.append(new AuditRecord(Instant.now(), AuditKind.SUBMIT_REJECTED,
                        source.name(), base.sourceVersion(), base.sourceVersion(),
                        detail, active.get() == null ? -1L : active.get().version()));
                throw new ChangeRejectedException(vr.issues().get(0).reason(), detail);
            }

            long newSourceVersion = source.replace(
                    new SourceSnapshot(source.name(), 0, vr.validEntries()));
            MergedConfig published = reload();
            auditLog.append(new AuditRecord(Instant.now(), AuditKind.SUBMIT_ACCEPTED,
                    source.name(), request.expectedSourceVersion(), newSourceVersion,
                    "requestId=" + request.requestId() + "，" + vr.validEntries().size()
                            + " 个条目生效，发布配置 v" + published.version(),
                    published.version()));
            log.info("变更已接受：来源[{}] v{}→v{}，配置发布 v{} (requestId={})",
                    source.name(), request.expectedSourceVersion(), newSourceVersion,
                    published.version(), request.requestId());
            return published;
        }
    }

    private ChangeRejectedException rejected(ChangeRequest request, RejectReason reason, String message) {
        auditLog.append(new AuditRecord(Instant.now(), AuditKind.SUBMIT_REJECTED,
                request.sourceName(), request.expectedSourceVersion(), null,
                message + " (requestId=" + request.requestId() + ")",
                active.get() == null ? -1L : active.get().version()));
        return new ChangeRejectedException(reason, message);
    }

    /**
     * 回滚到指定配置版本：恢复该版本各来源状态，重新合并发布为新版本。
     */
    public MergedConfig rollback(long targetVersion) {
        synchronized (writeLock) {
            List<SourceSnapshot> targetStates = sourceStatesAtVersion.get(targetVersion);
            if (targetStates == null) {
                throw new ChangeRejectedException(RejectReason.STALE_VERSION,
                        "找不到配置版本 v" + targetVersion + "，无法回滚");
            }
            MergedConfig current = active.get();
            if (current != null && current.version() == targetVersion) {
                throw new ChangeRejectedException(RejectReason.NO_OP_CHANGE,
                        "v" + targetVersion + " 就是当前生效版本，无需回滚");
            }

            for (SourceSnapshot state : targetStates) {
                ConfigSource source = sourceByName.get(state.sourceName());
                if (source != null) {
                    source.replace(state);
                }
            }
            MergedConfig published = reload();
            auditLog.append(new AuditRecord(Instant.now(), AuditKind.ROLLBACK,
                    "*", current == null ? null : current.version(), published.version(),
                    "回滚到历史版本 v" + targetVersion + "，恢复 " + targetStates.size()
                            + " 个来源的当时状态",
                    published.version()));
            log.info("已回滚：{} → v{}（基于历史 v{}）",
                    current == null ? "-" : "v" + current.version(),
                    published.version(), targetVersion);
            return published;
        }
    }

    public MergedConfig current() {
        return active.get();
    }
}
