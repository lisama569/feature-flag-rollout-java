package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.rollout.Bucketing;
import com.github.lisama569.featureflagrolloutjava.rollout.Decision;
import com.github.lisama569.featureflagrolloutjava.rollout.FeatureFlagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureFlagServiceTest {
    private static final Logger log = LoggerFactory.getLogger(FeatureFlagServiceTest.class);

    private InMemoryConfigSource source;
    private ConfigService configService;
    private FeatureFlagService flags;

    @BeforeEach
    void setUp() {
        source = new InMemoryConfigSource("dynamic", 100);
        configService = new ConfigService(List.of(source), new ConfigValidator(), new AuditLog());
        flags = new FeatureFlagService(configService);
    }

    @Test
    void masterSwitchOff_disablesEveryone() {
        source.setEntries(Map.of(
                "feature.pay.enabled", "false",
                "feature.pay.percentage", "100"));
        configService.refresh();
        Decision d = flags.evaluate("pay", "user-1");
        log.info("[test] 输入: flag=pay subject=user-1 | 依据: {} | 结果: enabled={}", d.reason(), d.enabled());
        assertFalse(d.enabled());
    }

    @Test
    void fullRollout_enablesEveryone_zeroRolloutDisablesEveryone() {
        source.setEntries(Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "100"));
        configService.refresh();
        for (int i = 0; i < 200; i++) {
            assertTrue(flags.isEnabled("pay", "user-" + i), "100% 放量应全员命中");
        }
        long base = configService.currentSnapshot().version();
        try {
            configService.submitChange(new ChangeRequest("to-zero", "test", base,
                    Map.of("feature.pay.percentage", "0")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < 200; i++) {
            assertFalse(flags.isEnabled("pay", "user-" + i), "0% 放量应全员不命中");
        }
    }

    @Test
    void sameSubject_stableAcrossEvaluationsWhileConfigUnchanged() {
        source.setEntries(Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "50"));
        configService.refresh();
        for (int i = 0; i < 50; i++) {
            String subject = "user-" + i;
            boolean first = flags.isEnabled("pay", subject);
            for (int j = 0; j < 20; j++) {
                assertEquals(first, flags.isEnabled("pay", subject),
                        "配置版本不变时同一主体结果必须稳定");
            }
        }
    }

    @Test
    void percentageRollout_hitsRoughlyExpectedProportion_andMatchesBucketRule() {
        source.setEntries(Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "30"));
        configService.refresh();
        int hits = 0;
        int total = 5000;
        for (int i = 0; i < total; i++) {
            String subject = "user-" + i;
            boolean expected = Bucketing.bucket("pay", subject) < 30;
            boolean actual = flags.isEnabled("pay", subject);
            assertEquals(expected, actual, "判定结果必须与分桶规则一致");
            if (actual) {
                hits++;
            }
        }
        double ratio = hits * 100.0 / total;
        log.info("[test] 输入: percentage=30 total={} | 依据: bucket<30 | 结果: 命中比例={}%", total, ratio);
        assertTrue(ratio > 25 && ratio < 35, "命中比例应接近 30%，实际 " + ratio + "%");
    }

    @Test
    void decision_isExplainable() {
        source.setEntries(Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "50"));
        configService.refresh();
        Decision d = flags.evaluate("pay", "user-42");
        log.info("[test] 输入: flag=pay subject=user-42 | 依据: {} | 结果: enabled={} version={}",
                d.reason(), d.enabled(), d.configVersion());
        assertEquals(configService.currentSnapshot().version(), d.configVersion());
        assertEquals(Bucketing.bucket("pay", "user-42"), d.bucket());
        assertEquals(50, d.percentage());
        assertEquals(d.bucket() < 50, d.enabled());
    }

    @Test
    void rolloutIncrease_onlyMigratesBoundarySubjects() {
        source.setEntries(Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "20"));
        configService.refresh();
        Set<String> before = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            if (flags.isEnabled("pay", "user-" + i)) {
                before.add("user-" + i);
            }
        }
        long base = configService.currentSnapshot().version();
        try {
            configService.submitChange(new ChangeRequest("up", "test", base,
                    Map.of("feature.pay.percentage", "40")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        for (int i = 0; i < 1000; i++) {
            String subject = "user-" + i;
            if (before.contains(subject)) {
                assertTrue(flags.isEnabled("pay", subject),
                        "放量比例提升后，原命中主体不得掉出: " + subject);
            }
        }
        List.of("done").forEach(s -> log.info("[test] 输入: 20%->40% | 依据: 桶号稳定 | 结果: 原 {} 个命中主体全部保留", before.size()));
    }
}
