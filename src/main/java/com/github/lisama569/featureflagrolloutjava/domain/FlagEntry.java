package com.github.lisama569.featureflagrolloutjava.domain;

/**
 * 特性开关定义。
 *
 * @param enabled 开关总开关（false 时所有主体取 defaultValue）
 * @param rolloutPercentage 灰度放量比例，[0,100]
 * @param defaultValue 未灰度命中时的取值
 * @param variantKey 可选实验变体键，用于区分不同实验分流
 */
public record FlagEntry(String key, boolean enabled, int rolloutPercentage,
                        boolean defaultValue, String variantKey) implements ConfigEntry {
}
