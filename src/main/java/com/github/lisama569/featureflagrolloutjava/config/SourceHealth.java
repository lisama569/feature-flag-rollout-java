package com.github.lisama569.featureflagrolloutjava.config;

/**
 * 单个配置来源最近一次拉取的健康状况，供值班总览展示降级状态。
 */
public record SourceHealth(String name, int priority, boolean degraded,
                           Long lastSuccessMillis, Long lastFailureMillis,
                           String lastFailureReason, int lastGoodEntryCount) {
}
