package com.github.lisama569.featureflagrolloutjava.operations;

/**
 * 总览中的最近一条发布活动摘要（来自审计记录）。
 *
 * @param seq        审计序号
 * @param changeId   变更标识
 * @param source     来源
 * @param operator   操作者（可能为 null）
 * @param outcome    结果：APPLIED / REJECTED / ROLLED_BACK
 * @param fromVersion 前一版本
 * @param toVersion  后一版本（拒绝时与 fromVersion 相同）
 * @param detail     明细摘要
 * @param timestampMillis 时间戳
 */
public record RecentActivity(long seq, String changeId, String source, String operator,
                             String outcome, long fromVersion, long toVersion,
                             String detail, long timestampMillis) {
}
