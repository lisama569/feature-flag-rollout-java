package com.github.lisama569.featureflagrolloutjava.rollout;

/** 一次分流判定的可解释结果：输入、判定依据与最终生效结果。 */
public record Decision(String flagKey, String subjectId, boolean enabled, int bucket,
                       int percentage, long configVersion, String reason) {
}
