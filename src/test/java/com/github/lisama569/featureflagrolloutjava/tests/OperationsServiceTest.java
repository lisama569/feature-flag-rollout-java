package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.BatchChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.SourceStatus;
import com.github.lisama569.featureflagrolloutjava.operations.FlagOverview;
import com.github.lisama569.featureflagrolloutjava.operations.OperationsService;
import com.github.lisama569.featureflagrolloutjava.operations.SystemOverview;
import com.github.lisama569.featureflagrolloutjava.release.ReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 值班运行总览：打开开关展示、筛选/容量截断、来源降级下仍可用。 */
class OperationsServiceTest {
    private static final Logger log = LoggerFactory.getLogger(OperationsServiceTest.class);

    private InMemoryConfigSource defaults;
    private InMemoryConfigSource dynamic;
    private ConfigService configService;
    private OperationsService ops;
    private ReleaseService release;

    @BeforeEach
    void setUp() {
        defaults = new InMemoryConfigSource("defaults", 0);
        defaults.setEntries(Map.of(
                "feature.pay.enabled", "false",
                "feature.pay.percentage", "0"));
        dynamic = new InMemoryConfigSource("dynamic", 100);
        dynamic.setEntries(Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "40",
                "feature.search.enabled", "true",
                "feature.search.percentage", "100",
                "feature.hidden.enabled", "false",
                "feature.hidden.percentage", "0"));
        AuditLog auditLog = new AuditLog();
        configService = new ConfigService(List.of(defaults, dynamic), new ConfigValidator(), auditLog);
        ops = new OperationsService(configService);
        release = new ReleaseService(configService);
    }

    @Test
    void overview_showsCurrentVersionOpenFlagsAndPercentages() {
        SystemOverview o = ops.overview(null, null, null, null);
        log.info("[test] 输入: 总览 | 结果: version={} banner={} openFlags={}",
                o.currentVersion(), o.statusBanner(),
                o.openFlags().stream().map(f -> f.flag() + ":" + f.percentage()).toList());

        assertFalse(o.degraded());
        assertEquals(configService.currentSnapshot().version(), o.currentVersion());
        Set<String> openNames = new java.util.HashSet<>(o.openFlags().stream().map(FlagOverview::flag).toList());
        assertEquals(Set.of("pay", "search"), openNames, "只展示 enabled=true 的开关");
        FlagOverview pay = o.openFlags().stream().filter(f -> f.flag().equals("pay")).findFirst().orElseThrow();
        assertEquals(40, pay.percentage());
        assertEquals("CANARY", pay.rolloutState());
        FlagOverview search = o.openFlags().stream().filter(f -> f.flag().equals("search")).findFirst().orElseThrow();
        assertEquals("FULL", search.rolloutState());
    }

    @Test
    void overview_recentActivity_canFilterByOutcome() {
        long v = configService.currentSnapshot().version();
        assertDoesNotThrow(() -> release.publishBatch(new BatchChangeRequest("o-1", "test", "alice", v,
                Map.of("feature.pay.percentage", "50"))));
        long v2 = configService.currentSnapshot().version();
        // 制造一条拒绝记录（过期）
        try {
            release.publishBatch(new BatchChangeRequest("o-stale", "test", "bob", v,
                    Map.of("feature.pay.percentage", "60")));
        } catch (Exception ignore) { }

        SystemOverview applied = ops.overview(
                Set.of(com.github.lisama569.featureflagrolloutjava.config.AuditRecord.Action.APPLIED),
                null, null, 50);
        SystemOverview rejected = ops.overview(
                Set.of(com.github.lisama569.featureflagrolloutjava.config.AuditRecord.Action.REJECTED),
                null, null, 50);

        log.info("[test] 输入: 按结果筛选 | 结果: applied={} rejected={}",
                applied.recentActivity().size(), rejected.recentActivity().size());
        assertTrue(applied.recentActivity().stream().allMatch(a -> a.outcome().equals("APPLIED")));
        assertTrue(rejected.recentActivity().stream().allMatch(a -> a.outcome().equals("REJECTED")));
        assertTrue(applied.recentActivity().stream().anyMatch(a -> a.operator().equals("alice")));
        assertTrue(rejected.recentActivity().stream().anyMatch(a -> a.operator().equals("bob")));
        assertEquals(v2, configService.currentSnapshot().version());
    }

    @Test
    void overview_respectsLimitAndSignalsTruncation() {
        long v = configService.currentSnapshot().version();
        for (int i = 0; i < 10; i++) {
            long base = configService.currentSnapshot().version();
            final int idx = i;
            assertDoesNotThrow(() -> release.publishBatch(new BatchChangeRequest("o-many-" + idx, "test",
                    "op", base, Map.of("feature.pay.percentage", String.valueOf(40 + idx)))));
        }
        SystemOverview limited = ops.overview(null, null, null, 3);
        log.info("[test] 输入: limit=3 共10条发布 | 结果: returned={} total={} truncated={}",
                limited.activityReturned(), limited.activityTotalMatched(), limited.activityTruncated());
        assertEquals(3, limited.activityReturned());
        assertTrue(limited.activityTotalMatched() >= 10);
        assertTrue(limited.activityTruncated());
        assertEquals(3, limited.activityLimit());
    }

    @Test
    void overview_availableWhenSourceDegraded_andClearlyMarked() {
        // 先让 dynamic 成功一次，再让它不可用，refresh 触发降级
        assertEquals("40", configService.get("feature.pay.percentage"));
        dynamic.setAvailable(false);
        ConfigSnapshot refreshed = configService.refresh();
        assertEquals("40", refreshed.get("feature.pay.percentage"), "降级仍用最近可用快照");

        SystemOverview o = ops.overview(null, null, null, null);
        log.info("[test] 输入: dynamic 来源不可用 | 结果: degraded={} banner={}", o.degraded(), o.statusBanner());
        assertTrue(o.degraded(), "总览必须明确标注当前为降级状态");
        assertTrue(o.statusBanner().contains("降级"));
        SourceStatus bad = o.sources().stream().filter(s -> s.name().equals("dynamic")).findFirst().orElseThrow();
        assertEquals(SourceStatus.STATE_DEGRADED, bad.state());
        assertTrue(bad.degraded());
        assertTrue(bad.lastSuccessMillis() > 0, "要能看到最近一次可用时间");
        // 总览本身仍可用，且仍能给出当前开关状态
        assertEquals(configService.currentSnapshot().version(), o.currentVersion());
    }

}
