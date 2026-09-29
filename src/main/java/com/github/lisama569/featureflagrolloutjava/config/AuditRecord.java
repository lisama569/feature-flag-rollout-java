package com.github.lisama569.featureflagrolloutjava.config;

import java.util.List;
import java.util.Map;

/**
 * 审计记录：每次变更（应用/拒绝/回滚）的来源、操作者、版本与结果。
 *
 * <p>{@code operator} 与 {@code affectedKeys} 为批次原子发布引入；
 * {@code entries} 为事后复盘引入，记录该动作完成后<b>整批涉及键的完整键值</b>
 * （批次发布为整批合并后的发布值；回滚为整批恢复后的值；拒绝时为空 map）。
 * 旧构造路径下 operator 为 source、affectedKeys/entries 为空，保持对旧调用方的兼容。</p>
 *
 * @param seq           全局递增序号
 * @param changeId      变更/批次标识（回滚为 rollback-to-&lt;version&gt;）
 * @param source        来源
 * @param operator      操作者
 * @param fromVersion   变更前版本
 * @param toVersion     变更后版本
 * @param action        结果：APPLIED / REJECTED / ROLLED_BACK
 * @param detail        文字说明
 * @param affectedKeys  本次动作涉及的键（被拒绝时可能为请求中的键，也可能为空）
 * @param entries       动作完成后 affectedKeys 对应的完整键值（成功发布/回滚时有值；拒绝时为空 map）
 * @param timestampMillis 时间戳
 */
public record AuditRecord(long seq, String changeId, String source, String operator,
                          long fromVersion, long toVersion,
                          Action action, String detail,
                          List<String> affectedKeys, Map<String, String> entries,
                          long timestampMillis) {
    public enum Action { APPLIED, REJECTED, ROLLED_BACK }

    /** 旧签名兼容：operator 取 source，affectedKeys/entries 为空。 */
    public AuditRecord(long seq, String changeId, String source, long fromVersion, long toVersion,
                       Action action, String detail, long timestampMillis) {
        this(seq, changeId, source, source, fromVersion, toVersion, action, detail,
                List.of(), Map.of(), timestampMillis);
    }

    /** 批次引入后的兼容签名：有 operator/affectedKeys，但没有完整键值（entries 为空）。 */
    public AuditRecord(long seq, String changeId, String source, String operator,
                       long fromVersion, long toVersion,
                       Action action, String detail, List<String> affectedKeys,
                       long timestampMillis) {
        this(seq, changeId, source, operator, fromVersion, toVersion, action, detail,
                affectedKeys, Map.of(), timestampMillis);
    }
}
