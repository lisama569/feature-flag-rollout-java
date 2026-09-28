package com.github.lisama569.featureflagrolloutjava.config;

/**
 * 单个配置项在“试算基准快照 -> 试算合并结果”之间的差异。
 * changeType 取值：ADDED（新增）/ UPDATED（修改）/ UNCHANGED（无变化）。
 */
public record EntryDiff(String key, String currentValue, String proposedValue, String changeType) {
}
