package com.github.lisama569.featureflagrolloutjava.rollout;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 一次分流判定的可解释结果：输入、判定依据与最终生效结果。 */
public record Decision(
        @JsonProperty("flagKey") String flagKey,
        @JsonProperty("subjectId") String subjectId,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("bucket") int bucket,
        @JsonProperty("percentage") int percentage,
        @JsonProperty("configVersion") long configVersion,
        @JsonProperty("reason") String reason) {
}
