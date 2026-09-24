package com.github.lisama569.featureflagrolloutjava.config;

import java.util.Map;

/** 一个配置来源。优先级数值越大越优先。 */
public interface ConfigSource {
    String name();
    int priority();
    /** 拉取该来源的原始配置；不可用时抛出 {@link ConfigSourceUnavailableException}。 */
    Map<String, String> load() throws ConfigSourceUnavailableException;
}
