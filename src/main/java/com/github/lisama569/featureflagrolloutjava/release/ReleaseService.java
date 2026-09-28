package com.github.lisama569.featureflagrolloutjava.release;

import com.github.lisama569.featureflagrolloutjava.config.BatchChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.ValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 发布编排：发布前试算与批次原子发布。
 *
 * <p>试算为只读操作：不改变当前生效配置、不写可回滚历史、不登记 changeId，
 * 因此不占用真实发布的幂等位置。批次发布委托 {@link ConfigService} 在同一把写锁内
 * 整批完成，读者只会看到完整旧结果或完整新结果。</p>
 */
public class ReleaseService {
    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    static final String CHANGE_ADDED = "ADDED";
    static final String CHANGE_MODIFIED = "MODIFIED";
    static final String CHANGE_REJECTED = "REJECTED";
    static final String CHANGE_UNCHANGED = "UNCHANGED";

    private final ConfigService configService;

    public ReleaseService(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 发布前试算。基于当前生效版本给出合并后的预计结果、逐项拒绝原因、
     * 受影响开关与灰度变化，并与当前值对比。绝不修改任何状态。
     */
    public PreflightResult preflight(BatchChangeRequest request) {
        // 一次性读取当前快照：试算结论与预计视图都基于同一个版本，不会前后自相矛盾
        ConfigSnapshot current = configService.currentSnapshot();
        long currentVersion = current.version();
        Map<String, String> currentEntries = current.entries();

        PreflightStatus status;
        String message;
        if (configService.isAppliedChangeId(request.changeId())) {
            status = PreflightStatus.DUPLICATE_CHANGE_ID;
            message = "changeId=" + request.changeId() + " 此前已真实发布，重复标识再次出现将被拒绝；"
                    + "当前生效配置未被改动";
        } else if (request.baseVersion() != currentVersion) {
            status = PreflightStatus.STALE_BASE_VERSION;
            message = "基准版本 " + request.baseVersion() + " 已过期，当前生效版本为 " + currentVersion
                    + "；若以此提交会被拒绝，当前生效配置未被改动";
        } else if (request.entries() == null || request.entries().isEmpty()) {
            status = PreflightStatus.EMPTY_BATCH;
            message = "拟变更批次为空，未包含任何配置项";
        } else {
            List<ValidationError> errors = configService.validator().validate(request.entries());
            if (!errors.isEmpty()) {
                status = PreflightStatus.INVALID_ENTRIES;
                message = "拟变更包含 " + errors.size() + " 个非法配置项；当前生效配置未被改动";
            } else {
                status = PreflightStatus.APPLYABLE;
                message = "试算通过：若立即正式发布，整批 " + request.entries().size()
                        + " 项将一次生效并形成版本 " + (currentVersion + 1);
            }
        }

        List<ValidationError> errors = (request.entries() == null || request.entries().isEmpty())
                ? List.of()
                : configService.validator().validate(request.entries());
        Map<String, ValidationError> errorByKey = new LinkedHashMap<>();
        for (ValidationError e : errors) {
            errorByKey.put(e.key(), e);
        }

        // 预计合并结果：合法项叠加到当前值；非法项不参与（正式发布时整批拒绝，这里仅作逐项展示）
        Map<String, String> projected = new LinkedHashMap<>(currentEntries);
        for (Map.Entry<String, String> e : request.entries().entrySet()) {
            if (!errorByKey.containsKey(e.getKey())) {
                projected.put(e.getKey(), e.getValue());
            }
        }
        // 只有在真正可发布时才给出“整份预计生效配置”；非法/过期/重复时该视图可能误导，故置空
        boolean applyable = status == PreflightStatus.APPLYABLE;
        Map<String, String> projectedEntries = applyable
                ? Map.copyOf(new TreeMap<>(projected))
                : Map.of();
        long projectedVersion = applyable ? currentVersion + 1 : currentVersion;

        List<ItemDiff> itemDiffs = buildItemDiffs(request, currentEntries, errorByKey);
        List<FlagImpact> impacts = buildFlagImpacts(currentEntries,
                applyable ? projected : currentEntries, applyable);

        PreflightResult result = new PreflightResult(status, applyable, request.changeId(),
                request.source(), request.operator(), request.baseVersion(), currentVersion,
                message, List.copyOf(itemDiffs), List.copyOf(impacts),
                projectedEntries, projectedVersion);
        log.info("[preflight] 输入: changeId={} source={} operator={} baseVersion={} 项数={} | "
                        + "依据: 当前版本={} 非法项={} | 结果: status={} wouldApply={} 受影响开关={}",
                request.changeId(), request.source(), request.operator(), request.baseVersion(),
                request.entries().size(), currentVersion, errors.size(),
                status, applyable, impacts.stream().map(FlagImpact::flag).toList());
        return result;
    }

    private List<ItemDiff> buildItemDiffs(BatchChangeRequest request, Map<String, String> currentEntries,
                                          Map<String, ValidationError> errorByKey) {
        List<ItemDiff> diffs = new ArrayList<>();
        for (Map.Entry<String, String> e : request.entries().entrySet()) {
            String key = e.getKey();
            String incoming = e.getValue();
            String cur = currentEntries.get(key);
            ValidationError err = errorByKey.get(key);
            if (err != null) {
                diffs.add(new ItemDiff(CHANGE_REJECTED, key, cur, null, false,
                        err.code().name(), err.message()));
            } else if (cur == null) {
                diffs.add(new ItemDiff(CHANGE_ADDED, key, null, incoming, true, null, null));
            } else if (!cur.equals(incoming)) {
                diffs.add(new ItemDiff(CHANGE_MODIFIED, key, cur, incoming, true, null, null));
            } else {
                diffs.add(new ItemDiff(CHANGE_UNCHANGED, key, cur, incoming, true, null, null));
            }
        }
        return diffs;
    }

    private List<FlagImpact> buildFlagImpacts(Map<String, String> currentEntries,
                                              Map<String, String> projectedEntries,
                                              boolean applyable) {
        Set<String> flags = new TreeSet<>();
        collectFlags(currentEntries, flags);
        collectFlags(projectedEntries, flags);

        List<FlagImpact> impacts = new ArrayList<>();
        for (String flag : flags) {
            boolean curEnabled = enabledOf(currentEntries, flag);
            int curPct = percentageOf(currentEntries, flag);
            boolean projEnabled = enabledOf(projectedEntries, flag);
            int projPct = percentageOf(projectedEntries, flag);
            boolean enabledChanged = curEnabled != projEnabled;
            int delta = projPct - curPct;
            if (applyable && (enabledChanged || delta != 0)) {
                impacts.add(new FlagImpact(flag, curEnabled, projEnabled, curPct, projPct,
                        enabledChanged, delta));
            }
        }
        return impacts;
    }

    private void collectFlags(Map<String, String> entries, Set<String> flags) {
        for (String key : entries.keySet()) {
            String flag = flagOf(key);
            if (flag != null) {
                flags.add(flag);
            }
        }
    }

    /** 仅识别 feature.&lt;name&gt;.(enabled|percentage)，其余 key 不属于开关维度。 */
    private String flagOf(String key) {
        String prefix = "feature.";
        if (!key.startsWith(prefix)) {
            return null;
        }
        String rest = key.substring(prefix.length());
        if (rest.endsWith(".enabled")) {
            return rest.substring(0, rest.length() - ".enabled".length());
        }
        if (rest.endsWith(".percentage")) {
            return rest.substring(0, rest.length() - ".percentage".length());
        }
        return null;
    }

    private boolean enabledOf(Map<String, String> entries, String flag) {
        String raw = entries.get("feature." + flag + ".enabled");
        return raw != null && Boolean.parseBoolean(raw.trim());
    }

    private int percentageOf(Map<String, String> entries, String flag) {
        String raw = entries.get("feature." + flag + ".percentage");
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 批次原子发布：整批生效或整批保持原样，只形成一个可回滚版本。
     * 全部判定与切换在 ConfigService 的写锁内完成；并发的试算/判定只会看到完整旧或完整新版本。
     */
    public ConfigSnapshot publishBatch(BatchChangeRequest request) throws ChangeRejectedException {
        return configService.submitBatch(request);
    }
}
