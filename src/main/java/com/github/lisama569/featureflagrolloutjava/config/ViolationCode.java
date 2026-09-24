package com.github.lisama569.featureflagrolloutjava.config;

/** 可区分的配置校验失败原因。 */
public enum ViolationCode {
    EMPTY_KEY,
    NULL_VALUE,
    NOT_AN_INTEGER,
    OUT_OF_RANGE,
    NOT_A_BOOLEAN
}
