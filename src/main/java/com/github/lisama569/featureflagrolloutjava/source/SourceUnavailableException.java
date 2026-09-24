package com.github.lisama569.featureflagrolloutjava.source;

/**
 * 配置来源不可用（拉取失败等）。
 */
public class SourceUnavailableException extends RuntimeException {
    public SourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
