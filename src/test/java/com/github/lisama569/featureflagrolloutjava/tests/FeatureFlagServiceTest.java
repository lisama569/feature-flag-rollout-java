package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.audit.InMemoryAuditLog;
import com.github.lisama569.featureflagrolloutjava.audit.InMemorySnapshotHistory;
import com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry;
import com.github.lisama569.featureflagrolloutjava.domain.DegradedSource;
import com.github.lisama569.featureflagrolloutjava.domain.EvaluationResult;
import com.github.lisama569.featureflagrolloutjava.domain.FlagEntry;
import com.github.lisama569.featureflagrolloutjava.domain.PropertyEntry;
import com.github.lisama569.featureflagrolloutjava.merge.PriorityConfigMerger;
import com.github.lisama569.featureflagrolloutjava.rollout.FnvBucketer;
import com.github.lisama569.featureflagrolloutjava.service.FeatureFlagService;
import com.github.lisama569.featureflagrolloutjava.source.MapConfigSource;
import com.github.lisama569.featureflagrolloutjava.validation.StrictConfigValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心服务测试（一）：分层加载、灰度判定稳定性、来源不可用降级。
 * 每个用例都打印输入、判定依据与最终生效结果。
 */
class FeatureFlagServiceTest {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagServiceTest.class);

    private MapConfigSource local;
    private MapConfigSource remote;
    private FeatureFlagService service;

    @BeforeEach
    void setUp() {
        local = new MapConfigSource("local-defaults", 0, List.of(
                new PropertyEntry("endpoint", "http://local"),
                new FlagEntry("new-checkout", false, 0, false, "checkout-exp"),
                new FlagEntry("dark-mode", true, 50, false, "ui-exp")));
        remote = new MapConfigSource("remote", 100, List.of(
                new PropertyEntry("endpoint", "http://remote")));
        service = new FeatureFlagService(
                List.of(local, remote),
                new StrictConfigValidator(),
                new PriorityConfigMerger(),
                new FnvBucketer(),
                new InMemoryAuditLog(),
                new InMemorySnapshotHistory());
        service.reload();
    }

    @Test
    void bootstrapLoadsLayeredValues() {
        String endpoint = service.getProperty("endpoint").orElseThrow();
        log.info("输入: 读取 endpoint | 判定依据: remote(prio=100) 覆盖 local(prio=0) | 最终生效: {}", endpoint);
        assertEquals("http://remote", endpoint);
    }

    @Test
    void disabledFlagAlwaysReturnsDefault() {
        EvaluationResult r = service.evaluate("new-checkout", "any-user");
        log.info("输入: flag=new-checkout, subject=any-user | 判定依据: {} | 最终生效: value={}",
                r.reason(), r.value());
        assertFalse(r.value());
        assertEquals(service.current().version(), r.configVersion());
    }

    @Test
    void percentageZeroAndHundredAreDeterministic() {
        remote.replace(new com.github.lisama569.featureflagrolloutjava.domain.SourceSnapshot(
                "remote", 0, List.of(new FlagEntry("dark-mode", true, 0, false, "ui-exp"))));
        service.reload();
        for (int i = 0; i < 50; i++) {
            assertFalse(service.evaluate("dark-mode", "u" + i).value());
        }
        remote.replace(new com.github.lisama569.featureflagrolloutjava.domain.SourceSnapshot(
                "remote", 0, List.of(new FlagEntry("dark-mode", true, 100, false, "ui-exp"))));
        service.reload();
        int hitCount = 0;
        for (int i = 0; i < 200; i++) {
            EvaluationResult r = service.evaluate("dark-mode", "u" + i);
            if (r.value()) {
                hitCount++;
            }
        }
        log.info("输入: 放量 0% 与 100% | 判定依据: 桶值 [0,100) 与边界比较 | 最终生效: 0%命中0人, 100%命中{}/200人",
                hitCount);
        assertEquals(200, hitCount);
    }

    @Test
    void sameSubjectStableAcrossRepeatedReads() {
        long v = service.current().version();
        boolean first = service.evaluate("dark-mode", "stable-user").value();
        for (int i = 0; i < 100; i++) {
            EvaluationResult r = service.evaluate("dark-mode", "stable-user");
            assertEquals(first, r.value());
            assertEquals(v, r.configVersion(), "配置版本未变时结果必须一致");
        }
        log.info("输入: dark-mode/stable-user 读取101次, 配置版本 v{} 不变 | 判定依据: 同一快照+确定性哈希 | 最终生效: 全部={}",
                v, first);
    }

    @Test
    void rolloutDistributionIsRoughlyProportional() {
        int hits = 0;
        int n = 5_000;
        for (int i = 0; i < n; i++) {
            if (service.evaluate("dark-mode", "user-" + i).value()) {
                hits++;
            }
        }
        double ratio = hits * 100.0 / n;
        log.info("输入: dark-mode 放量50%, {}个主体 | 判定依据: 桶值<50 命中 | 最终生效: 命中{}人, 比例={}%",
                n, hits, String.format("%.1f", ratio));
        assertTrue(ratio > 45 && ratio < 55, "50% 放量的实际比例应在 45%~55%，实际 " + ratio);
    }

    @Test
    void unavailableSourceDegradesToLastGoodSnapshot() {
        // 先让 remote 成功加载过一次（setUp 中 endpoint=http://remote）
        assertEquals("http://remote", service.getProperty("endpoint").orElseThrow());
        remote.setAvailable(false);
        var merged = service.reload();
        log.info("输入: remote 置为不可用后重载 | 判定依据: 使用 remote 最近可用快照 | 最终生效: endpoint={}, 降级记录={}",
                service.getProperty("endpoint").orElseThrow(), merged.degraded());
        assertEquals("http://remote", service.getProperty("endpoint").orElseThrow(),
                "来源不可用必须降级到最近一次可用快照而不是失败");
        assertEquals(1, merged.degraded().size());
        DegradedSource ds = merged.degraded().get(0);
        assertEquals("remote", ds.sourceName());
        assertTrue(ds.usedSourceVersion() >= 0, "必须记录降级所使用的缓存来源版本");
        log.info("降级记录校验通过: 使用 remote v{} 的缓存快照", ds.usedSourceVersion());
    }

    @Test
    void invalidEntryFromHighPrioritySourceIsRejectedNotOverriding() {
        remote.replace(new com.github.lisama569.featureflagrolloutjava.domain.SourceSnapshot(
                "remote", 0, List.<ConfigEntry>of(
                        new PropertyEntry("endpoint", "   "),
                        new FlagEntry("bad-flag", true, 200, false, "exp"))));
        var merged = service.reload();
        log.info("输入: remote 提供空白 endpoint 与 200% 非法开关 | 判定依据: 非法条目逐条拒绝 | 最终生效: endpoint={}, rejected={}",
                service.getProperty("endpoint").orElseThrow(), merged.rejected());
        assertEquals("http://local", service.getProperty("endpoint").orElseThrow(),
                "高优先级来源非法值不得静默覆盖低优先级生效值");
        assertEquals(2, merged.rejected().size());
    }
}
