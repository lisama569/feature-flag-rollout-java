package com.github.lisama569.featureflagrolloutjava.ops;

import java.util.List;

/**
 * 面向值班人员的运行总览。
 *
 * @param currentVersion          当前生效版本
 * @param degraded                是否有任一来源处于降级
 * @param sources                 各来源状态（含最近一次可用情况）
 * @param recentActions           最近的发布成功/拒绝/回滚摘要（有容量上限）
 * @param recentLimit             recentActions 的容量上限
 * @param recentTruncated         是否因容量上限发生截断
 * @param recentTotalAvailable    截断前可用的近期动作总数
 * @param openFlags               当前打开的开关与灰度比例
 * @param historyRetention        可回滚历史的保留上限（版本数）
 * @param historyRetainedVersions 当前仍保留、可回滚的版本数
 * @param historyEvictedCount     因保留上限被淘汰的历史版本总数（&gt;0 即发生过淘汰）
 * @param earliestRetainedVersion 当前可回滚的最早版本号
 * @param generatedAtMillis       总览生成时间
 */
public record OperationsOverview(long currentVersion,
                                 boolean degraded,
                                 List<SourceStatus> sources,
                                 List<RecentAction> recentActions,
                                 int recentLimit,
                                 boolean recentTruncated,
                                 int recentTotalAvailable,
                                 List<OpenFlagView> openFlags,
                                 int historyRetention,
                                 int historyRetainedVersions,
                                 long historyEvictedCount,
                                 long earliestRetainedVersion,
                                 long generatedAtMillis) {
}
