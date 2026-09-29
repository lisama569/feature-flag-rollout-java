package com.github.lisama569.featureflagrolloutjava.config;

import java.util.List;
import java.util.Map;

/**
 * 审计记录：每次变更（应用/拒绝/回滚）的来源、操作者、版本与结果。
 * operator、affectedKeys 为批次原子发布引入；affectedEntries 进一步给出
 * 每个键最终发布成的值，使一条批次记录即可完整复盘“整批发布了什么”。
 * 旧构造路径下 operator 为 source、affectedKeys 为空列表、affectedEntries 为空表，
 * 保持对旧调用方的兼容。
 */
public record AuditRecord(long seq, String changeId, String source, String operator,
                          long fromVersion, long toVersion,
                          Action action, String detail,
                          List<String> affectedKeys, Map<String, String> affectedEntries,
                          long timestampMillis) {
    public enum Action { APPLIED, REJECTED, ROLLED_BACK }

    /** 兼容签名：不带键值明细，affectedEntries 为空表。 */
    public AuditRecord(long seq, String changeId, String source, String operator,
                       long fromVersion, long toVersion,
                       Action action, String detail,
                       List<String> affectedKeys, long timestampMillis) {
        this(seq, changeId, source, operator, fromVersion, toVersion, action, detail,
                affectedKeys, Map.of(), timestampMillis);
    }

    /** 旧签名兼容：operator 取 source，affectedKeys 为空、affectedEntries 为空。 */
    public AuditRecord(long seq, String changeId, String source, long fromVersion, long toVersion,
                       Action action, String detail, long timestampMillis) {
        this(seq, changeId, source, source, fromVersion, toVersion, action, detail,
                List.of(), Map.of(), timestampMillis);
    }
}
