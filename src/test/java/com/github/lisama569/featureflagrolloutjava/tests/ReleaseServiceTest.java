package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.BatchChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.RejectionReason;
import com.github.lisama569.featureflagrolloutjava.release.FlagImpact;
import com.github.lisama569.featureflagrolloutjava.release.ItemDiff;
import com.github.lisama569.featureflagrolloutjava.release.PreflightStatus;
import com.github.lisama569.featureflagrolloutjava.release.PreflightResult;
import com.github.lisama569.featureflagrolloutjava.release.ReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发布前试算与批次原子发布的语义测试。 */
class ReleaseServiceTest {
    private static final Logger log = LoggerFactory.getLogger(ReleaseServiceTest.class);

    private InMemoryConfigSource defaults;
    private InMemoryConfigSource dynamic;
    private AuditLog auditLog;
    private ConfigService configService;
    private ReleaseService release;

    @BeforeEach
    void setUp() {
        defaults = new InMemoryConfigSource("defaults", 0);
        defaults.setEntries(Map.of(
                "feature.pay.enabled", "false",
                "feature.pay.percentage", "10",
                "feature.cart.enabled", "false",
                "feature.cart.percentage", "0"));
        dynamic = new InMemoryConfigSource("dynamic", 100);
        auditLog = new AuditLog();
        configService = new ConfigService(List.of(defaults, dynamic), new ConfigValidator(), auditLog);
        release = new ReleaseService(configService);
    }

    private BatchChangeRequest batch(String id, long base, Map<String, String> entries) {
        return new BatchChangeRequest(id, "release-test", "alice", base, entries);
    }

    @Test
    void preflight_applyable_doesNotChangeStateOrConsumeIdempotencySlot() {
        long versionBefore = configService.currentSnapshot().version();
        int auditBefore = auditLog.records().size();

        PreflightResult r = release.preflight(batch("pf-1", versionBefore, Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "40")));

        log.info("[test] 输入: 试算 pay=true,40 | 依据: status={} | 结果: wouldApply={} projectedVersion={}",
                r.status(), r.wouldApply(), r.projectedVersion());
        assertEquals(PreflightStatus.APPLYABLE, r.status());
        assertTrue(r.wouldApply());
        // 试算不改变当前生效配置
        assertEquals(versionBefore, configService.currentSnapshot().version());
        assertEquals("false", configService.get("feature.pay.enabled"));
        assertEquals("10", configService.get("feature.pay.percentage"));
        // 试算不写审计、不登记 changeId
        assertEquals(auditBefore, auditLog.records().size());
        assertFalse(configService.isAppliedChangeId("pf-1"));
        // 试算后同一 changeId 仍能真实发布（未被占用幂等位置）
        ConfigSnapshot published = assertDoesNotThrow(() -> release.publishBatch(
                batch("pf-1", versionBefore, Map.of(
                        "feature.pay.enabled", "true",
                        "feature.pay.percentage", "40"))));
        assertEquals(versionBefore + 1, published.version());
    }

    @Test
    void preflight_applyable_reportsDiffsAndFlagImpact() {
        long base = configService.currentSnapshot().version();
        PreflightResult r = release.preflight(batch("pf-2", base, Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "60")));

        ItemDiff enabledDiff = r.itemDiffs().stream()
                .filter(d -> d.key().equals("feature.pay.enabled")).findFirst().orElseThrow();
        ItemDiff pctDiff = r.itemDiffs().stream()
                .filter(d -> d.key().equals("feature.pay.percentage")).findFirst().orElseThrow();
        FlagImpact impact = r.impactedFlags().stream()
                .filter(f -> f.flag().equals("pay")).findFirst().orElseThrow();

        log.info("[test] 输入: pay false/10 -> true/60 | 结果: diffs={} impact={}",
                r.itemDiffs(), impact);
        assertEquals("MODIFIED", enabledDiff.changeType());
        assertEquals("false", enabledDiff.currentValue());
        assertEquals("true", enabledDiff.projectedValue());
        assertEquals("MODIFIED", pctDiff.changeType());
        assertTrue(impact.currentlyEnabled() == false && impact.projectedEnabled());
        assertEquals(10, impact.currentPercentage());
        assertEquals(60, impact.projectedPercentage());
        assertEquals(50, impact.percentageDelta());
        assertTrue(impact.enabledChanged());
        assertEquals("true", r.projectedEntries().get("feature.pay.enabled"));
        assertEquals("60", r.projectedEntries().get("feature.pay.percentage"));
    }

    @Test
    void preflight_staleBase_isDistinguishableAndDoesNotApply() {
        long base = configService.currentSnapshot().version();
        // 先真实发布一次，使版本前进
        assertDoesNotThrow(() -> release.publishBatch(batch("real-1", base,
                Map.of("feature.pay.percentage", "20"))));

        PreflightResult r = release.preflight(batch("pf-stale", base,
                Map.of("feature.pay.percentage", "90")));
        log.info("[test] 输入: 过期基准 {} | 结果: status={} msg={}", base, r.status(), r.message());
        assertEquals(PreflightStatus.STALE_BASE_VERSION, r.status());
        assertFalse(r.wouldApply());
        assertEquals(base + 1, r.currentVersion());
        assertTrue(r.projectedEntries().isEmpty(), "过期时不应给出可能误导的预计整份配置");
        assertEquals("20", configService.get("feature.pay.percentage"));
    }

    @Test
    void preflight_duplicateChangeId_isDistinguishableFromStale() {
        long base = configService.currentSnapshot().version();
        assertDoesNotThrow(() -> release.publishBatch(batch("dup-id", base,
                Map.of("feature.pay.percentage", "30"))));
        long current = configService.currentSnapshot().version();

        // 即使基准版本正确，changeId 重复也要单独识别为 DUPLICATE_CHANGE_ID
        PreflightResult r = release.preflight(batch("dup-id", current,
                Map.of("feature.pay.percentage", "80")));
        log.info("[test] 输入: 重复 changeId=dup-id 基准正确={} | 结果: status={}", current, r.status());
        assertEquals(PreflightStatus.DUPLICATE_CHANGE_ID, r.status());
        assertFalse(r.wouldApply());
        assertEquals("30", configService.get("feature.pay.percentage"));
    }

    @Test
    void preflight_invalidEntries_listsPerItemReasons() {
        long base = configService.currentSnapshot().version();
        PreflightResult r = release.preflight(batch("pf-bad", base, Map.of(
                "feature.pay.percentage", "abc",
                "feature.cart.enabled", "maybe")));

        log.info("[test] 输入: 非法批次 | 结果: status={} rejected={}",
                r.status(), r.itemDiffs().stream().filter(d -> !d.accepted()).toList());
        assertEquals(PreflightStatus.INVALID_ENTRIES, r.status());
        assertFalse(r.wouldApply());
        List<ItemDiff> rejected = r.itemDiffs().stream().filter(d -> !d.accepted()).toList();
        assertEquals(2, rejected.size());
        assertNotNull(r.itemDiffs().stream()
                .filter(d -> d.key().equals("feature.pay.percentage"))
                .findFirst().orElseThrow().rejectionCode());
        // 非法试算不改变生效值
        assertEquals("10", configService.get("feature.pay.percentage"));
    }

    @Test
    void publishBatch_allOrNothing_invalidBatchKeepsEverything() {
        long base = configService.currentSnapshot().version();
        Map<String, String> batch = Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "200", // 非法
                "feature.cart.enabled", "true");
        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class,
                () -> release.publishBatch(batch("batch-bad", base, batch)));
        assertEquals(RejectionReason.INVALID_ENTRIES, ex.reason());
        log.info("[test] 输入: 含非法项的多开关批次 | 依据: {} | 结果: 整批保持原样", ex.reason());
        // 连批次中合法的部分也不得先生效
        assertEquals(base, configService.currentSnapshot().version());
        assertEquals("false", configService.get("feature.pay.enabled"));
        assertEquals("false", configService.get("feature.cart.enabled"));
        assertEquals("10", configService.get("feature.pay.percentage"));
    }

    @Test
    void publishBatch_appliesWholeBatchAsSingleVersion() {
        long base = configService.currentSnapshot().version();
        ConfigSnapshot after = assertDoesNotThrow(() -> release.publishBatch(batch("batch-ok", base,
                Map.of("feature.pay.enabled", "true",
                        "feature.pay.percentage", "55",
                        "feature.cart.enabled", "true",
                        "feature.cart.percentage", "100"))));
        log.info("[test] 输入: 4 项跨 2 开关批次 | 结果: 单版本 {} 全部生效", after.version());
        assertEquals(base + 1, after.version(), "整批只能形成一个新版本");
        assertEquals("true", after.get("feature.pay.enabled"));
        assertEquals("55", after.get("feature.pay.percentage"));
        assertEquals("true", after.get("feature.cart.enabled"));
        assertEquals("100", after.get("feature.cart.percentage"));
    }

    @Test
    void rollback_afterBatch_restoresWholeBatchContent() {
        long v0 = configService.currentSnapshot().version();
        assertDoesNotThrow(() -> release.publishBatch(batch("batch-rb", v0,
                Map.of("feature.pay.enabled", "true",
                        "feature.pay.percentage", "77",
                        "feature.cart.enabled", "true"))));
        assertEquals("77", configService.get("feature.pay.percentage"));
        assertEquals("true", configService.get("feature.cart.enabled"));

        ConfigSnapshot rolled = configService.rollback(v0, "oncall-bob");
        log.info("[test] 输入: 回滚批次到 v{} | 结果: 整批内容恢复", v0);
        // 回滚恢复的是整批内容，不能只回一部分
        assertEquals("false", rolled.get("feature.pay.enabled"));
        assertEquals("10", rolled.get("feature.pay.percentage"));
        assertEquals("false", rolled.get("feature.cart.enabled"));
    }

}
