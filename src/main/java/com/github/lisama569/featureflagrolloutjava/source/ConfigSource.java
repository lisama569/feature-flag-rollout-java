package com.github.lisama569.featureflagrolloutjava.source;

import com.github.lisama569.featureflagrolloutjava.domain.SourceSnapshot;

/**
 * 一个可热更新的配置来源。
 */
public interface ConfigSource {
    /** 来源名称，唯一。 */
    String name();

    /** 来源优先级，数值越大优先级越高。 */
    int priority();

    /** 拉取当前完整快照；来源不可用时抛 {@link SourceUnavailableException}。 */
    SourceSnapshot load();

    /** 用新快照替换来源内容，返回新的来源版本。 */
    long replace(SourceSnapshot snapshot);
}
