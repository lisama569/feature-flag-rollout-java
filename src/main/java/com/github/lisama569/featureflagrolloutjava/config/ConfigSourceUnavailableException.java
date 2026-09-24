package com.github.lisama569.featureflagrolloutjava.config;

/** 配置来源不可用时抛出，调用方应降级到最近一次可用快照。 */
public class ConfigSourceUnavailableException extends Exception {
    public ConfigSourceUnavailableException(String message) { super(message); }
    public ConfigSourceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
