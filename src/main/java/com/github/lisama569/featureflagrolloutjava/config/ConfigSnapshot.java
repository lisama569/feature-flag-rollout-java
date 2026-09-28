package com.github.lisama569.featureflagrolloutjava.config;

import java.util.Map;

/**
 * 不可变的生效配置快照。所有字段在构造时深拷贝为不可变视图，
 * 切换时整体原子替换，读取方不会观察到半新半旧的中间状态。
 */
public record ConfigSnapshot(long version, Map<String, String> entries,
                             Map<String, Long> sourceVersions, long createdAtMillis) {

    public ConfigSnapshot {
        entries = Map.copyOf(entries);
        sourceVersions = Map.copyOf(sourceVersions);
    }

    public String get(String key) { return entries.get(key); }
}
