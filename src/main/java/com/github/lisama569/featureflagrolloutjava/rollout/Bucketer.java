package com.github.lisama569.featureflagrolloutjava.rollout;

/**
 * 灰度分流器：把 (开关键, 分流主体, 实验变体) 稳定映射到 [0,100) 桶。
 */
public interface Bucketer {
    int bucket(String flagKey, String subject, String variantKey);
}
