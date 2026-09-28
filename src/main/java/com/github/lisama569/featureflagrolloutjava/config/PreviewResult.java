package com.github.lisama569.featureflagrolloutjava.config;

import java.util.List;

/**
 * 发布前试算结果（只描述“假如现在发布会怎样”，绝不改变当前生效配置、历史与幂等记录）。
 *
 * @param wouldApply        若正式提交是否会被接受
 * @param rejectionReason   被拒绝的可区分原因（可接受时为 null）
 * @param message           面向人的说明
 * @param baseVersion       提交方声称基于的版本
 * @param currentVersion    试算时看到的当前生效版本
 * @param proposedVersion   预计发布后的版本号（wouldApply=false 时为 null）
 * @param errors            非法配置项明细（每项拒绝原因可区分）
 * @param diffs             逐项当前值 vs 预计值
 * @param affectedFlags     受影响开关及其灰度变化
 */
public record PreviewResult(boolean wouldApply,
                            RejectionReason rejectionReason,
                            String message,
                            long baseVersion,
                            long currentVersion,
                            Long proposedVersion,
                            List<ValidationError> errors,
                            List<EntryDiff> diffs,
                            List<FlagImpact> affectedFlags) {
}
