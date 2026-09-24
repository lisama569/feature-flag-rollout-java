package com.github.lisama569.featureflagrolloutjava.domain;

import java.util.List;
import java.util.Map;

/**
 * 合并后生效的不可变配置快照。读取方拿到的永远是某一个完整快照，
 * 不存在半新半旧的中间状态。
 *
 * @param version 合并快照的全局单调版本
 * @param flags 生效的特性开关
 * @param properties 生效的普通属性
 * @param rejected 本轮被拒绝的非法条目（仅作可解释性记录，不影响生效值）
 * @param degraded 本轮降级的来源信息
 */
public record MergedConfig(long version,
                           Map<String, FlagEntry> flags,
                           Map<String, PropertyEntry> properties,
                           List<ValidationIssue> rejected,
                           List<DegradedSource> degraded) {
}
