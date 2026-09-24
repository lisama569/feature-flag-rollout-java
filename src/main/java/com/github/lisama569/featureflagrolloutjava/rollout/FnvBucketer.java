package com.github.lisama569.featureflagrolloutjava.rollout;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 基于 FNV-1a 64 位哈希的稳定分流器。
 * <p>
 * 哈希输入为 {@code flagKey|variantKey|subject}：
 * <ul>
 *   <li>同一主体在开关定义（键、实验变体）不变时永远落入同一桶 —— 配置版本不变则结果稳定；</li>
 *   <li>不掺入合并配置版本号，因此一次纯来源热更新（开关定义未变）不会无谓地重分流；</li>
 *   <li>放量比例变化时，只有跨过新百分比边界的主体会改变结果，符合灰度放量语义。</li>
 * </ul>
 */
@Component
public class FnvBucketer implements Bucketer {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    @Override
    public int bucket(String flagKey, String subject, String variantKey) {
        String input = safe(flagKey) + '|' + safe(variantKey) + '|' + safe(subject);
        long hash = FNV_OFFSET_BASIS;
        for (byte b : input.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xffL);
            hash *= FNV_PRIME;
        }
        return (int) Long.remainderUnsigned(hash, 100L);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
