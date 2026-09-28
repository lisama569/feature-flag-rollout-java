package com.github.lisama569.featureflagrolloutjava.ops;

/** 总览中展示的最近一次发布/拒绝/回滚摘要。 */
public record RecentAction(String action, String changeId, String source, String operator,
                           long fromVersion, long toVersion, long timestampMillis, String detail) {
}
