package com.github.lisama569.featureflagrolloutjava.domain;

/**
 * 普通键值属性。
 */
public record PropertyEntry(String key, String value) implements ConfigEntry {
}
