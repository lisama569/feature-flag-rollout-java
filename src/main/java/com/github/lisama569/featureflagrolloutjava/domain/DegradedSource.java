package com.github.lisama569.featureflagrolloutjava.domain;

/**
 * 降级信息：来源不可用时记录用了什么旧快照顶替。
 */
public record DegradedSource(String sourceName, String detail, long usedSourceVersion) {
}
