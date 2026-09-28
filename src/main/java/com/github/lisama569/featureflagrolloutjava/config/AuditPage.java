package com.github.lisama569.featureflagrolloutjava.config;

import java.util.List;

/**
 * 审计查询的一页结果（不可变）。带容量上限与截断提示，
 * 保证值班查询不会随历史无限增长而吃内存。
 *
 * @param records        本次返回的记录（时间倒序，最新在前）
 * @param returned       实际返回条数
 * @param totalMatched   满足筛选条件的总条数（含未返回部分）
 * @param limit          本次请求的容量上限
 * @param truncated      是否因达到上限被截断
 * @param droppedTotal   因环形缓冲容量而被整体丢弃的更早历史条数
 * @param oldestSeqKept  当前缓冲中最早保留记录的序号（无记录为 -1）
 */
public record AuditPage(List<AuditRecord> records, int returned, int totalMatched, int limit,
                        boolean truncated, int droppedTotal, long oldestSeqKept) {
}
