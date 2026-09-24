package com.github.lisama569.featureflagrolloutjava.domain;

import java.util.List;

/**
 * 针对某个来源的变更提交（乐观并发）。
 *
 * @param requestId 幂等键：重复提交同一 requestId 必须被拒绝
 * @param sourceName 目标来源
 * @param expectedSourceVersion 调用方基于哪个来源版本做的修改；过期则拒绝
 * @param entries 期望生效的该来源完整条目集
 */
public record ChangeRequest(String requestId, String sourceName, long expectedSourceVersion,
                            List<ConfigEntry> entries) {
}
