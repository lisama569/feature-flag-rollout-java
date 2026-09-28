package com.github.lisama569.featureflagrolloutjava.operations;

import com.github.lisama569.featureflagrolloutjava.config.SourceStatus;

import java.util.List;

/**
 * 面向值班人员的运行总览（不可变）。
 *
 * <p>即使某个配置来源不可用，总览本身仍可生成：降级状态在 {@code degraded} 与
 * {@code sources} 中显式标出，并通过 {@code statusBanner} 给出明确人类提示，
 * 避免值班人员把降级数据误认为全量正常数据。</p>
 *
 * @param currentVersion   当前生效版本
 * @param snapshotAtMillis 当前快照生成时间
 * @param degraded         是否有任一来源处于降级
 * @param statusBanner     状态横幅：正常或明确的降级说明
 * @param sources          各来源健康/降级状态及最近一次可用情况
 * @param openFlags        当前打开（enabled=true）的开关及其灰度比例
 * @param recentActivity   最近发布成功/拒绝/回滚摘要（受容量上限约束）
 * @param activityReturned 本次返回活动条数
 * @param activityTotalMatched 满足筛选的活动总数
 * @param activityLimit    活动查询容量上限
 * @param activityTruncated 活动是否因上限被截断
 * @param auditDroppedTotal 审计环形缓冲已挤出的更老记录总数
 * @param generatedAtMillis 总览生成时间
 */
public record SystemOverview(long currentVersion, long snapshotAtMillis, boolean degraded,
                             String statusBanner, List<SourceStatus> sources,
                             List<FlagOverview> openFlags, List<RecentActivity> recentActivity,
                             int activityReturned, int activityTotalMatched, int activityLimit,
                             boolean activityTruncated, int auditDroppedTotal,
                             long generatedAtMillis) {
}
