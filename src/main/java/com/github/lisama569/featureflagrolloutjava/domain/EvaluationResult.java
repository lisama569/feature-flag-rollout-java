package com.github.lisama569.featureflagrolloutjava.domain;

/**
 * 一次开关判定的可解释结果。
 *
 * @param flagKey 开关键
 * @param subject 分流主体
 * @param value 最终生效取值
 * @param on 是否命中开启态
 * @param bucket 分流桶值 [0,100)
 * @param percentage 判定依据的放量比例
 * @param configVersion 判定依据的配置版本
 * @param reason 判定依据说明
 */
public record EvaluationResult(String flagKey, String subject, boolean value, boolean on,
                               int bucket, int percentage, long configVersion, String reason) {
}
