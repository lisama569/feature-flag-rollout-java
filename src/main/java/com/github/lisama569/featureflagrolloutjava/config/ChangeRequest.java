package com.github.lisama569.featureflagrolloutjava.config;

import java.util.Map;

/**
 * 一次配置变更提交。
 * changeId 用于幂等去重；baseVersion 为提交方基于的配置版本，用于过期检测。
 */
public record ChangeRequest(String changeId, String source, long baseVersion, Map<String, String> entries) {
}
