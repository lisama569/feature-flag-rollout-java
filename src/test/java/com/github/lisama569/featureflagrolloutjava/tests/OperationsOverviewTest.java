package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.SourceHealth;
import com.github.lisama569.featureflagrolloutjava.ops.AuditPage;
import com.github.lisama569.featureflagrolloutjava.ops.OpenFlagView;
import com.github.lisama569.featureflagrolloutjava.ops.OperationsOverview;
import com.github.lisama569.featureflagrolloutjava.ops.OperationsOverviewService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 值班总览：降级仍可用、打开开关/近期摘要可见；审计查询有界、截断提示与筛选正确。 */
class OperationsOverviewTest {
    private static final Logger log = LoggerFactory.getLogger(OperationsOverviewTest.class);

    @Test
    void degradedSource_overviewStillAvailable_andClearlyMarked() throws Exception {
        InMemoryConfigSource defaults = new InMemoryConfigSource("defaults", 0);
        defaults.setEntries(Map.of(
                "feature.pay.enabled", "false", "feature.pay.percentage", "0"));
        InMemoryConfigSource dynamic = new InMemoryConfigSource("dynamic", 100);
        dynamic.setEntries(Map.of(
                "feature.pay.enabled", "true", "feature.pay.percentage", "50",
                "feature.off.enabled", "false", "feature.off.percentage", "100"));
        ConfigService service = new ConfigService(List.of(defaults, dynamic),
                new ConfigValidator(), new AuditLog());
        OperationsOverviewService ops = new OperationsOverviewService(service);

        long base = service.currentSnapshot().version();
        service.submitChange(new ChangeRequest("c1", "pipeline", base,
                Map.of("feature.pay.percentage", "60")));

        // 让高优先级来源不可用，触发降级
        dynamic.setAvailable(false);
        service.refresh();

        OperationsOverview overview = ops.overview(); // 总览本身必须可用
        SourceHealth dynamicHealth = service.sourceHealth().stream()
                .filter(h -> h.name().equals("dynamic")).findFirst().orElseThrow();

        log.info("[test] 输入: dynamic 不可用 | 依据: 降级标记+最近成功时间 | 结果: overview.degraded={} source.degraded={} lastSuccess={}",
                overview.degraded(), dynamicHealth.degraded(), dynamicHealth.lastSuccessMillis());
        assertTrue(overview.degraded(), "总览必须明确标注当前是降级状态");
        assertTrue(dynamicHealth.degraded());
        assertNotNull(dynamicHealth.lastSuccessMillis());
        assertNotNull(dynamicHealth.lastFailureMillis());
        assertNotNull(dynamicHealth.lastFailureReason());
        assertTrue(dynamicHealth.lastGoodEntryCount() > 0);
        assertEquals(service.currentSnapshot().version(), overview.currentVersion());

        // 当前打开的开关与灰度比例（off 总开关为 false，不应出现）
        // 注意：refresh 会从各来源重新合并，dynamic 降级到最近一次成功快照(percentage=50)，
        // 因此运行期 submit 的 60 不来自任何来源、会被来源合并结果取代——这是既有降级语义。
        OpenFlagView pay = overview.openFlags().stream()
                .filter(f -> f.flag().equals("pay")).findFirst().orElseThrow();
        assertTrue(pay.enabled());
        assertEquals(50, pay.percentage());
        assertTrue(overview.openFlags().stream().noneMatch(f -> f.flag().equals("off")));

        // 近期摘要里能看到成功发布
        assertTrue(overview.recentActions().stream().anyMatch(a -> a.changeId().equals("c1")
                && a.action().equals("APPLIED")));
    }

    @Test
    void overview_recentActionsHaveLimitAndTruncationHint() throws Exception {
        ConfigService service = new ConfigService(List.of(new InMemoryConfigSource("dynamic", 100)),
                new ConfigValidator(), new AuditLog());
        OperationsOverviewService ops = new OperationsOverviewService(service);
        // 制造多于 RECENT_LIMIT 的审计记录（用过期提交制造 REJECTED，简单可靠）
        for (int i = 0; i < OperationsOverviewService.RECENT_LIMIT + 15; i++) {
            long v = service.currentSnapshot().version();
            try {
                service.submitChange(new ChangeRequest("rej-" + i, "test", v + 999,
                        Map.of("k", "v")));
            } catch (Exception ignored) {
                // 预期拒绝
            }
        }
        OperationsOverview overview = ops.overview();
        log.info("[test] 输入: 制造 {} 条记录 | 依据: 近期上限={} | 结果: 返回={} 截断={} 总数={}",
                OperationsOverviewService.RECENT_LIMIT + 15, overview.recentLimit(),
                overview.recentActions().size(), overview.recentTruncated(),
                overview.recentTotalAvailable());
        assertEquals(OperationsOverviewService.RECENT_LIMIT, overview.recentActions().size());
        assertTrue(overview.recentTruncated());
        assertTrue(overview.recentTotalAvailable() > OperationsOverviewService.RECENT_LIMIT);
        // 最新在前
        long t0 = overview.recentActions().get(0).timestampMillis();
        long t1 = overview.recentActions().get(1).timestampMillis();
        assertTrue(t0 >= t1);
    }

    @Test
    void auditQuery_filtersByActionAndTime_isBoundedAndReportsTruncationAndEviction() {
        AuditLog tiny = new AuditLog(10); // 很小的保留容量，验证有界
        for (int i = 0; i < 25; i++) {
            AuditRecord.Action action = (i % 2 == 0) ? AuditRecord.Action.APPLIED : AuditRecord.Action.REJECTED;
            tiny.append("id-" + i, "src", 0, i, action, "d" + i);
        }
        // 只保留最近 10 条，淘汰 15 条，内存不会无限增长
        assertEquals(10, tiny.records().size());
        assertEquals(15, tiny.evictedCount());

        AuditPage applied = tiny.query(AuditRecord.Action.APPLIED, null, null, 100);
        log.info("[test] 输入: 25 条/容量 10 | 依据: 按 APPLIED 筛选 | 结果: 返回={} 匹配={} 淘汰={}",
                applied.records().size(), applied.totalMatched(), applied.evicted());
        assertFalse(applied.records().isEmpty());
        assertTrue(applied.records().stream().allMatch(r -> r.action() == AuditRecord.Action.APPLIED));
        assertEquals("APPLIED", applied.actionFilter());
        assertEquals(10, applied.retainedCapacity());

        // limit 截断提示
        AuditPage limited = tiny.query(null, null, null, 3);
        assertEquals(3, limited.records().size());
        assertTrue(limited.truncated());
        assertEquals(10, limited.totalMatched());
    }

    @Test
    void auditQuery_filtersByTimeWindow() throws InterruptedException {
        AuditLog auditLog = new AuditLog(50);
        auditLog.append("a", "s", 0, 1, AuditRecord.Action.APPLIED, "x");
        AuditRecord first = auditLog.records().get(0);
        Thread.sleep(5); // 保证两条记录时间戳不同
        auditLog.append("b", "s", 1, 2, AuditRecord.Action.APPLIED, "y");
        long boundary = first.timestampMillis() + 1;
        AuditPage direct = auditLog.query(null, boundary, null, 10);
        assertEquals(1, direct.records().size());
        assertEquals("b", direct.records().get(0).changeId());
        assertEquals(boundary, direct.fromMillis());
    }
}
