package com.github.lisama569.featureflagrolloutjava.config;

import java.util.List;
import java.util.Map;

/**
 * 发布前试算结果（只描述“假如现在发布会怎样”，绝不改变当前生效配置、历史与幂等记录）。
 *
 * <p>取值边界：只有当 {@code wouldApply=true} 时，{@code proposedVersion}、{@code diffs}、
 * {@code affectedFlags}、{@code mergedEntries} 才有值；一旦被拒绝（基准过期 / 内容非法 /
 * 标识已真实发布过），这些“预计会生效”的字段一律为空（版本为 null、集合为空、
 * 合并结果为 null），不会把注定不会生效的新值当作预计值返回，避免误判。</p>
 *
 * @param wouldApply        若正式提交是否会被接受
 * @param rejectionReason   被拒绝的可区分原因（可接受时为 null）
 * @param message           面向人的说明
 * @param baseVersion       提交方声称基于的版本
 * @param currentVersion    试算时看到的当前生效版本
 * @param proposedVersion   预计发布后的版本号（wouldApply=false 时为 null）
 * @param errors            非法配置项明细（每项拒绝原因可区分）
 * @param diffs             逐项当前值 vs 预计值（仅本次提交涉及的键；被拒绝时为空列表）
 * @param affectedFlags     受影响开关及其灰度变化（被拒绝时为空列表）
 * @param mergedEntries     按当前生效版本合并后的完整预计配置（被拒绝时为 null），
 *                          可直接与当前快照 {@code entries} 对照确认发布后整套配置
 */
public record PreviewResult(boolean wouldApply,
                            RejectionReason rejectionReason,
                            String message,
                            long baseVersion,
                            long currentVersion,
                            Long proposedVersion,
                            List<ValidationError> errors,
                            List<EntryDiff> diffs,
                            List<FlagImpact> affectedFlags,
                            Map<String, String> mergedEntries) {
}
