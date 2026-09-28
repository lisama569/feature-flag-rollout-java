package com.github.lisama569.featureflagrolloutjava.config;

/**
 * 试算后受影响开关的灰度变化：开关名、总开关与灰度比例的旧值/新值、变化类型。
 * changeType 取值：ADDED（新开关）/ UPDATED（配置变化）/ UNCHANGED（无变化）。
 */
public record FlagImpact(String flag,
                         Boolean enabledBefore, Integer percentageBefore,
                         Boolean enabledAfter, Integer percentageAfter,
                         String changeType) {
}
