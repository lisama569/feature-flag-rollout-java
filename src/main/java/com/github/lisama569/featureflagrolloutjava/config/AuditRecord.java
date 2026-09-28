package com.github.lisama569.featureflagrolloutjava.config;

/**
 * 审计记录：每次变更（应用/拒绝/回滚）的来源、操作者、版本与结果。
 *
 * @param operator 操作者；旧调用方未提供时为 null（此时可参考 source）
 */
public record AuditRecord(long seq, String changeId, String source, long fromVersion, long toVersion,
                          Action action, String detail, long timestampMillis, String operator) {
    public enum Action { APPLIED, REJECTED, ROLLED_BACK }

    /** 兼容旧调用方的构造：不携带操作者。 */
    public AuditRecord(long seq, String changeId, String source, long fromVersion, long toVersion,
                       Action action, String detail, long timestampMillis) {
        this(seq, changeId, source, fromVersion, toVersion, action, detail, timestampMillis, null);
    }
}
