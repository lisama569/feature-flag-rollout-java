package com.github.lisama569.featureflagrolloutjava.release;

import java.util.List;
import java.util.Map;

/**
 * 发布前试算结果（不可变）。
 *
 * <p>试算只读：不改动当前生效配置、不写可回滚历史、不占用 changeId 幂等位置，
 * 因此结果中同时给出 {@code wouldApply}（结论）与预计视图，调用方必须依据
 * {@link #status()} 判断是否真的可以发布，不能把预计结果当作已生效值。</p>
 *
 * @param status           试算结论（可发布 / 基准过期 / 重复标识 / 内容非法 / 空批次）
 * @param wouldApply       若立即正式发布，整批是否会被接受生效
 * @param changeId         试算使用的变更标识（不会被登记为已使用）
 * @param source           提交来源
 * @param operator         操作者（可为 null）
 * @param baseVersion      提交方声明的基准版本
 * @param currentVersion   试算时读到的当前生效版本
 * @param message          人类可读的总体说明
 * @param itemDiffs        逐项的当前值 vs 预计值对比（含每项被拒绝原因）
 * @param impactedFlags    受影响的开关及灰度变化
 * @param projectedEntries 若发布后预计生效的完整配置（仅在可发布/部分可算时有意义）
 * @param projectedVersion 若发布后预计形成的新版本号（仅在可发布时有意义；否则为当前版本）
 */
public record PreflightResult(PreflightStatus status, boolean wouldApply, String changeId,
                              String source, String operator, long baseVersion, long currentVersion,
                              String message, List<ItemDiff> itemDiffs, List<FlagImpact> impactedFlags,
                              Map<String, String> projectedEntries, long projectedVersion) {
}
