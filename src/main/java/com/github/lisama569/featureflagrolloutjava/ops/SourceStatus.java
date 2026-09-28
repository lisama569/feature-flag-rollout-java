package com.github.lisama569.featureflagrolloutjava.ops;

/**
 * 单个配置来源的运行状态。
 *
 * @param name                 来源名
 * @param priority             优先级
 * @param degraded             是否处于降级（最近一次拉取失败，正在使用历史快照或跳过）
 * @param lastSuccessMillis    最近一次成功拉取时间（从未成功为 null）
 * @param lastFailureMillis    最近一次失败时间（无失败为 null）
 * @param lastFailureReason    最近一次失败原因（无失败为 null）
 * @param lastGoodEntryCount   最近一次成功快照的条目数（从未成功为 0）
 */
public record SourceStatus(String name, int priority, boolean degraded,
                           Long lastSuccessMillis, Long lastFailureMillis,
                           String lastFailureReason, int lastGoodEntryCount) {
}
