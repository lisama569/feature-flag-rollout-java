package com.github.lisama569.featureflagrolloutjava.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 配置条目：要么是普通属性，要么是特性开关。
 * JSON 中通过 {@code type} 字段区分（PROPERTY / FLAG）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PropertyEntry.class, name = "PROPERTY"),
        @JsonSubTypes.Type(value = FlagEntry.class, name = "FLAG")
})
public sealed interface ConfigEntry permits PropertyEntry, FlagEntry {
    String key();
}
