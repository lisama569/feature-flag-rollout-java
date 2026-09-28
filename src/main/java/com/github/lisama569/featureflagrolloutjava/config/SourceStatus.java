package com.github.lisama569.featureflagrolloutjava.config;

/**
 * 单个配置来源当前的健康/降级状态（不可变快照视图）。
 *
 * @param name             来源名
 * @param priority         优先级
 * @param state            状态：OK（最近一次拉取成功）/ DEGRADED（最近一次失败，正使用最近可用快照）
 *                         / NO_SNAPSHOT（失败且从未成功，合并时被跳过）/ NEVER_LOADED（尚未拉取过）
 * @param degraded         是否正处于降级（用最近一次可用快照提供服务）
 * @param lastAttemptMillis 最近一次拉取时间；-1 表示从未尝试
 * @param lastSuccessMillis 最近一次成功拉取时间；-1 表示从未成功
 * @param lastGoodEntryCount 最近一次成功快照的条目数
 * @param lastError        最近一次失败的错误信息；无则为 null
 */
public record SourceStatus(String name, int priority, String state, boolean degraded,
                           long lastAttemptMillis, long lastSuccessMillis,
                           int lastGoodEntryCount, String lastError) {

    public static final String STATE_OK = "OK";
    public static final String STATE_DEGRADED = "DEGRADED";
    public static final String STATE_NO_SNAPSHOT = "NO_SNAPSHOT";
    public static final String STATE_NEVER_LOADED = "NEVER_LOADED";
}
