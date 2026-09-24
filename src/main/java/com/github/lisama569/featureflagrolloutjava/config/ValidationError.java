package com.github.lisama569.featureflagrolloutjava.config;

/** 单条非法配置项的校验结果。 */
public record ValidationError(String key, String value, ViolationCode code, String message) {
}
