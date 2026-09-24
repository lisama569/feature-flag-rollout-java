package com.github.lisama569.featureflagrolloutjava.rollout;

import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 特性开关与灰度放量。
 *
 * <p>每次判定先一次性取出当前生效快照（原子引用），再基于该快照完成全部判定，
 * 因此配置切换过程中不会出现新旧分支混用，读取方也不会观察到中间状态。</p>
 *
 * <p>配置约定：</p>
 * <ul>
 *   <li>{@code feature.<name>.enabled}：总开关，缺省 false</li>
 *   <li>{@code feature.<name>.percentage}：灰度比例 0-100，缺省 0</li>
 * </ul>
 */
public class FeatureFlagService {
    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    private final ConfigService configService;

    public FeatureFlagService(ConfigService configService) {
        this.configService = configService;
    }

    public Decision evaluate(String flagKey, String subjectId) {
        ConfigSnapshot snapshot = configService.currentSnapshot();
        String enabledRaw = snapshot.get("feature." + flagKey + ".enabled");
        String percentageRaw = snapshot.get("feature." + flagKey + ".percentage");

        boolean masterOn = Boolean.parseBoolean(enabledRaw == null ? "false" : enabledRaw.trim());
        int percentage = parsePercentage(percentageRaw);
        int bucket = Bucketing.bucket(flagKey, subjectId);

        final boolean enabled;
        final String reason;
        if (!masterOn) {
            enabled = false;
            reason = "总开关关闭(feature." + flagKey + ".enabled=" + enabledRaw + ")";
        } else {
            enabled = bucket < percentage;
            reason = "总开关开启，桶号 " + bucket + (enabled ? " < " : " >= ")
                    + "灰度比例 " + percentage;
        }
        Decision decision = new Decision(flagKey, subjectId, enabled, bucket, percentage,
                snapshot.version(), reason);
        log.info("[flag-eval] 输入: flag={} subject={} | 依据: 版本={} enabled={} percentage={} bucket={} | 结果: {} ({})",
                flagKey, subjectId, snapshot.version(), enabledRaw, percentage, bucket, enabled, reason);
        return decision;
    }

    public boolean isEnabled(String flagKey, String subjectId) {
        return evaluate(flagKey, subjectId).enabled();
    }

    private int parsePercentage(String raw) {
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
