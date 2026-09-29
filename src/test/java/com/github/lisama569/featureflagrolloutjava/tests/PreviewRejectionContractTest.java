package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.PreviewResult;
import com.github.lisama569.featureflagrolloutjava.config.RejectionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试算被拒绝时的强契约：
 * 被拒绝的试算只说明原因，绝不能把注定不生效的新开关值/新比例当作“预计值/受影响项”回带；
 * 试算本身不改变生效配置、历史版本、审计与真实发布的幂等占位。
 * 成功时则能拿到按当前版本合并后的完整预计配置，并与当前值逐键对照。
 */
class PreviewRejectionContractTest {
    private static final Logger log = LoggerFactory.getLogger(PreviewRejectionContractTest.class);

    private InMemoryConfigSource defaults;
    private InMemoryConfigSource dynamic;
    private AuditLog auditLog;
    private ConfigService service;
    private long base;

    @BeforeEach
    void setUp() {
        defaults = new InMemoryConfigSource("defaults", 0);
        defaults.setEntries(Map.of(
                "feature.checkout.enabled", "false",
                "feature.checkout.percentage", "10"));
        dynamic = new InMemoryConfigSource("dynamic", 100);
        auditLog = new AuditLog();
        service = new ConfigService(List.of(defaults, dynamic), new ConfigValidator(), auditLog);
        base = service.currentSnapshot().version();
    }

    /** 成功试算：返回合并后的完整预计配置（含未涉及键），并与当前值逐键对照。 */
    @Test
    void acceptedPreview_returnsFullMergedProjection_andCurrentSnapshotForComparison() {
        PreviewResult ok = service.previewChange(new ChangeRequest("ok-1", "test", base,
                Map.of("feature.checkout.percentage", "60",
                        "feature.fresh.enabled", "true")));

        assertTrue(ok.wouldApply());
        // 完整预计配置：既包含本次提交的新值，也保留未涉及的当前键
        assertEquals("60", ok.projectedEntries().get("feature.checkout.percentage"));
        assertEquals("true", ok.projectedEntries().get("feature.fresh.enabled"));
        assertEquals("false", ok.projectedEntries().get("feature.checkout.enabled"),
                "未涉及键必须按当前值带入完整预计配置");
        assertEquals(ok.currentEntries().size() + 1, ok.projectedEntries().size(),
                "完整预计配置 = 当前配置 + 本次新增键（checkout.percentage 为覆盖）");
        // 当前配置同时返回，便于直接对照
        assertEquals("10", ok.currentEntries().get("feature.checkout.percentage"));
        assertEquals(service.currentSnapshot().entries(), ok.currentEntries());
        // 仍能拿到逐项差异
        assertTrue(ok.diffs().stream().anyMatch(d -> d.key().equals("feature.fresh.enabled")
                && "ADDED".equals(d.changeType())));
        log.info("[test] 成功试算完整预计配置键={}", ok.projectedEntries().keySet());
    }

    @Test
    void stalePreview_doesNotEmitIneffectiveProjectionOrImpacts() {
        PreviewResult r = service.previewChange(new ChangeRequest("p-stale", "test", base + 3,
                Map.of("feature.checkout.percentage", "50",
                        "feature.ghost.enabled", "true")));
        assertFalse(r.wouldApply());
        assertEquals(RejectionReason.STALE_BASE_VERSION, r.rejectionReason());
        assertNull(r.proposedVersion());
        assertNull(r.projectedEntries(), "过期基准下不得回带预计完整配置");
        assertTrue(r.diffs().isEmpty(), "过期基准下不得把新值当作预计差异返回");
        assertTrue(r.affectedFlags().isEmpty(), "过期基准下不得把新开关列为受影响项");
        assertEquals("10", service.currentSnapshot().entries().get("feature.checkout.percentage"));
        assertNull(service.get("feature.ghost.enabled"));
        log.info("[test] 过期试算: diffs={} impacts={} projected={}",
                r.diffs().size(), r.affectedFlags().size(), r.projectedEntries());
    }

    @Test
    void duplicatePreview_doesNotEmitIneffectiveProjectionOrImpacts() throws Exception {
        service.submitChange(new ChangeRequest("real-dup", "ops", base,
                Map.of("feature.checkout.percentage", "20")));
        long v = service.currentSnapshot().version();
        PreviewResult r = service.previewChange(new ChangeRequest("real-dup", "ops", v,
                Map.of("feature.checkout.percentage", "99",
                        "feature.ghost.enabled", "true")));
        assertFalse(r.wouldApply());
        assertEquals(RejectionReason.DUPLICATE_CHANGE_ID, r.rejectionReason());
        assertNull(r.proposedVersion());
        assertNull(r.projectedEntries(), "重复标识下不得回带预计完整配置");
        assertTrue(r.diffs().isEmpty());
        assertTrue(r.affectedFlags().isEmpty());
        assertEquals("20", service.get("feature.checkout.percentage"), "当前值不被试算内容污染");
        log.info("[test] 重复试算: reason={} projected={}", r.rejectionReason(), r.projectedEntries());
    }

    @Test
    void invalidPreview_doesNotEmitIneffectiveProjectionOrImpacts_butKeepsErrors() {
        PreviewResult r = service.previewChange(new ChangeRequest("p-bad", "test", base,
                Map.of("feature.checkout.percentage", "200",
                        "feature.checkout.enabled", "maybe",
                        "feature.ghost.enabled", "true")));
        assertFalse(r.wouldApply());
        assertEquals(RejectionReason.INVALID_ENTRIES, r.rejectionReason());
        assertNull(r.proposedVersion());
        assertNull(r.projectedEntries(), "非法内容下不得回带预计完整配置");
        assertTrue(r.diffs().isEmpty(), "非法值不得作为预计差异返回");
        assertTrue(r.affectedFlags().isEmpty(), "非法/新增开关不得列为受影响项");
        // 但每条非法项的拒绝原因仍然要清楚给出（这是“说明原因”的一部分）
        assertEquals(2, r.errors().size());
        assertFalse(r.message().isBlank());
        // 当前值保持
        assertEquals("10", service.get("feature.checkout.percentage"));
        assertEquals("false", service.get("feature.checkout.enabled"));
        log.info("[test] 非法试算: errors={} diffs={} projected={}",
                r.errors().size(), r.diffs().size(), r.projectedEntries());
    }

    @Test
    void rejectedPreview_changesNoStateAtAll_notAuditHistoryOrIdempotency() throws Exception {
        int auditBefore = auditLog.records().size();
        var historyBefore = service.history().keySet();

        // 三种被拒绝的试算各来若干次
        service.previewChange(new ChangeRequest("p1", "test", base + 9, Map.of("k", "v")));
        service.previewChange(new ChangeRequest("p2", "test", base, Map.of("feature.x.percentage", "oops")));
        service.previewChange(new ChangeRequest("p3", "test", base,
                Map.of("feature.x.percentage", "999")));;

        // 生效配置与版本完全不变
        assertEquals(base, service.currentSnapshot().version());
        // 审计一条不增（试算不写审计）
        assertEquals(auditBefore, auditLog.records().size());
        // 历史版本集合不变（无可回滚版本产生）
        assertEquals(historyBefore, service.history().keySet());

        // 被拒绝试算用过的标识仍能用于真实发布 -> 未占用幂等占位
        var after = service.submitChange(new ChangeRequest("p3", "test", base,
                Map.of("feature.checkout.percentage", "33")));
        assertEquals(base + 1, after.version());
        assertEquals("33", service.get("feature.checkout.percentage"));
        log.info("[test] 三次被拒试算后: 版本仍={}->真实发布成功={}", base, after.version());
    }
}
