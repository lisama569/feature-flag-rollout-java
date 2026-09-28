package com.github.lisama569.featureflagrolloutjava.ops;

import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;

import java.util.List;

/**
 * 有界的审计查询结果：按条件筛选、带容量上限与截断提示，不随历史无限增长吃内存。
 *
 * @param records          当前页记录（默认按时间倒序）
 * @param limit            本次返回的容量上限
 * @param totalMatched     符合筛选条件的记录总数（含被截断部分；有界保留范围内）
 * @param truncated        是否因容量上限截断
 * @param retainedCapacity 审计日志保留容量
 * @param evicted          因容量上限被淘汰的最老记录数
 * @param actionFilter     结果筛选条件（APPLIED/REJECTED/ROLLED_BACK，null 表示不限）
 * @param fromMillis       时间筛选起点（含，null 表示不限）
 * @param toMillis         时间筛选终点（含，null 表示不限）
 */
public record AuditPage(List<AuditRecord> records, int limit, int totalMatched, boolean truncated,
                        int retainedCapacity, int evicted,
                        String actionFilter, Long fromMillis, Long toMillis) {
}
