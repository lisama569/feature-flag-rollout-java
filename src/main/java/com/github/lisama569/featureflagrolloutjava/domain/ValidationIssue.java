package com.github.lisama569.featureflagrolloutjava.domain;

/**
 * 单条校验问题：定位到来源/条目，并给出可区分的拒绝原因与人类可读说明。
 */
public record ValidationIssue(String sourceName, String entryKey, RejectReason reason, String detail) {
}
