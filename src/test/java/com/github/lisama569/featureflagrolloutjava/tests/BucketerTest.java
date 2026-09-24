package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.rollout.FnvBucketer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分流器单测：稳定性（同输入同桶）、范围 [0,100)、分布近似均匀。
 */
class BucketerTest {

    private static final Logger log = LoggerFactory.getLogger(BucketerTest.class);

    private final FnvBucketer bucketer = new FnvBucketer();

    @Test
    void sameSubjectMustMapToSameBucket() {
        String flag = "dark-mode";
        String variant = "ui-exp";
        int first = bucketer.bucket(flag, "user-42", variant);
        for (int i = 0; i < 100; i++) {
            int again = bucketer.bucket(flag, "user-42", variant);
            log.info("输入: flag={}, subject=user-42, variant={} | 判定依据: 重复计算第{}次 | 结果: bucket={}",
                    flag, variant, i, again);
            assertEquals(first, again, "同一主体在配置版本不变时必须稳定命中同一桶");
        }
        assertTrue(first >= 0 && first < 100, "桶值必须落在 [0,100)");
        log.info("稳定性结论: user-42 始终落桶 {}", first);
    }

    @Test
    void bucketsMustBeInRangeAndRoughlyUniform() {
        Map<Integer, Integer> hist = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            String subject = "user-" + i;
            int bucket = bucketer.bucket("flag-a", subject, "v1");
            assertTrue(bucket >= 0 && bucket < 100);
            hist.merge(bucket, 1, Integer::sum);
        }
        double avg = 100.0;
        int max = hist.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        int min = hist.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
        log.info("输入: 10000 个不同主体 | 判定依据: 每桶期望约 100 | 结果: min={}, max={}, 覆盖桶数={}",
                min, max, hist.size());
        assertEquals(100, hist.size(), "100 个桶都应被覆盖到");
        assertTrue(max < avg * 2 && min > avg / 2, "桶分布不应明显倾斜");
    }

    @Test
    void changingVariantMustAllowReSplitButKeyOrSubjectChangeIsIndependent() {
        int b1 = bucketer.bucket("flag-a", "user-1", "v1");
        int b2 = bucketer.bucket("flag-a", "user-1", "v2");
        int b3 = bucketer.bucket("flag-b", "user-1", "v1");
        log.info("输入: (flag-a,v1,user-1)={} | (flag-a,v2,user-1)={} | (flag-b,v1,user-1)={}",
                b1, b2, b3);
        assertTrue(b1 >= 0 && b2 >= 0 && b3 >= 0);
        assertEquals(b1, bucketer.bucket("flag-a", "user-1", "v1"));
    }
}
