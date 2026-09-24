package com.github.lisama569.featureflagrolloutjava.domain;

import java.util.List;

/**
 * 某个配置来源在某个时刻的完整快照。
 *
 * @param sourceName 来源名称
 * @param sourceVersion 来源自身的版本（单调递增）
 * @param entries 该来源提供的全部条目
 */
public record SourceSnapshot(String sourceName, long sourceVersion, List<ConfigEntry> entries) {
}
