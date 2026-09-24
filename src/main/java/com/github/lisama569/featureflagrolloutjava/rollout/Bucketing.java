package com.github.lisama569.featureflagrolloutjava.rollout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 稳定分桶：对 flagKey + subjectId 做 SHA-256 哈希取模，
 * 同一主体在同一 flag 下桶号恒定，与配置版本无关，
 * 因此灰度比例不变时主体命中结果稳定；比例调整时只有边界处主体迁移。
 */
public final class Bucketing {
    private Bucketing() { }

    /** 返回 [0,100) 的稳定桶号。 */
    public static int bucket(String flagKey, String subjectId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((flagKey + ":" + subjectId).getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) {
                h = (h << 8) | (digest[i] & 0xffL);
            }
            return (int) Math.floorMod(h, 100);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
