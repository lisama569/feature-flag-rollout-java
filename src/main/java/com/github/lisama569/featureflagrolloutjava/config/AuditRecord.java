package com.github.lisama569.featureflagrolloutjava.config;

/** 审计记录：每次变更（应用/拒绝/回滚）的来源、版本与结果。 */
public record AuditRecord(long seq, String changeId, String source, long fromVersion, long toVersion,
                          Action action, String detail, long timestampMillis) {
    public enum Action { APPLIED, REJECTED, ROLLED_BACK }
}
