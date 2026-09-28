package com.github.lisama569.featureflagrolloutjava.operations;

/**
 * 总览中的单个开关当前状态。
 *
 * @param flag       开关名
 * @param enabled    总开关是否打开
 * @param percentage 灰度比例 0-100
 * @param rolloutState 文字状态：OFF（关闭）/ CANARY（灰度中，0&lt;p&lt;100）/ FULL（100%）
 */
public record FlagOverview(String flag, boolean enabled, int percentage, String rolloutState) {
}
