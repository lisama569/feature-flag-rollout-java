package com.github.lisama569.featureflagrolloutjava.config;

import java.util.Map;

/**
 * 不可变的生效配置快照。所有字段在构造时深拷贝为不可变视图，
 * 切换时整体原子替换，读取方不会观察到半新半旧的中间状态。
 */
public final class ConfigSnapshot {
    private final long version;
    private final Map<String, String> entries;
    private final Map<String, Long> sourceVersions;
    private final long createdAtMillis;

    public ConfigSnapshot(long version, Map<String, String> entries,
                          Map<String, Long> sourceVersions, long createdAtMillis) {
        this.version = version;
        this.entries = Map.copyOf(entries);
        this.sourceVersions = Map.copyOf(sourceVersions);
        this.createdAtMillis = createdAtMillis;
    }

    public long version() { return version; }

    public Map<String, String> entries() { return entries; }

    /** 各来源最近一次成功合并时的版本，用于审计与降级解释。 */
    public Map<String, Long> sourceVersions() { return sourceVersions; }

    public long createdAtMillis() { return createdAtMillis; }

    public String get(String key) { return entries.get(key); }
}
