package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.EntryDiff;
import com.github.lisama569.featureflagrolloutjava.config.FlagImpact;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.PreviewResult;
import com.github.lisama569.featureflagrolloutjava.config.RejectionReason;
import com.github.lisama569.featureflagrolloutjava.config.ViolationCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发布前试算：预计结果与拒绝原因正确，且绝不改变生效配置、历史与幂等记录。 */
class PreviewServiceTest {
    private static final Logger log = LoggerFactory.getLogger(PreviewServiceTest.class);

    private InMemoryConfigSource defaults;
    private InMemoryConfigSource dynamic;
    private AuditLog auditLog;
    private ConfigService service;

    @BeforeEach
    void setUp() {
        defaults = new InMemoryConfigSource("defaults", 0);
        defaults.setEntries(Map.of(
                "feature.checkout.enabled", "false",
                "feature.checkout.percentage", "10"));
        dynamic = new InMemoryConfigSource("dynamic", 100);
        auditLog = new AuditLog();
        service = new ConfigService(List.of(defaults, dynamic), new ConfigValidator(), auditLog);
    }

    @Test
    void validPreview_reportsProjectedResultDiffsAndImpact_withoutChangingState() {
        long base = service.currentSnapshot().version();
        int auditBefore = auditLog.records().size();

        PreviewResult result = service.previewChange(new ChangeRequest("pv-1", "test", base,
                Map.of("feature.checkout.enabled", "true",
                        "feature.checkout.percentage", "60",
                        "feature.fresh.enabled", "true")));

        log.info("[test] 输入: 试算 enabled=true percentage=60 新增 fresh | 依据: wouldApply={} 预计版本={} | 结果: diffs={} impacts={}",
                result.wouldApply(), result.proposedVersion(),
                result.diffs().stream().map(EntryDiff::changeType).toList(),
                result.affectedFlags().stream().map(f -> f.flag() + ":" + f.changeType()).toList());

        assertTrue(result.wouldApply());
        assertNull(result.rejectionReason());
        assertEquals(base + 1, result.proposedVersion());
        assertEquals(base, result.currentVersion());

        // 当前生效配置完全不变
        assertEquals(base, service.currentSnapshot().version());
        assertEquals("false", service.get("feature.checkout.enabled"));
        assertEquals("10", service.get("feature.checkout.percentage"));
        assertNull(service.get("feature.fresh.enabled"));
        // 不写审计、不产生历史版本
        assertEquals(auditBefore, auditLog.records().size());
        assertFalse(service.history().containsKey(base + 1));

        // 逐项差异
        EntryDiff enabledDiff = result.diffs().stream()
                .filter(d -> d.key().equals("feature.checkout.enabled")).findFirst().orElseThrow();
        assertEquals("false", enabledDiff.currentValue());
        assertEquals("true", enabledDiff.proposedValue());
        assertEquals("UPDATED", enabledDiff.changeType());
        EntryDiff addedDiff = result.diffs().stream()
                .filter(d -> d.key().equals("feature.fresh.enabled")).findFirst().orElseThrow();
        assertEquals("ADDED", addedDiff.changeType());
        assertNull(addedDiff.currentValue());

        // 受影响开关灰度变化
        FlagImpact checkout = result.affectedFlags().stream()
                .filter(f -> f.flag().equals("checkout")).findFirst().orElseThrow();
        assertEquals(false, checkout.enabledBefore());
        assertEquals(10, checkout.percentageBefore());
        assertEquals(true, checkout.enabledAfter());
        assertEquals(60, checkout.percentageAfter());
        assertEquals("UPDATED", checkout.changeType());
        FlagImpact fresh = result.affectedFlags().stream()
                .filter(f -> f.flag().equals("fresh")).findFirst().orElseThrow();
        assertEquals("ADDED", fresh.changeType());
    }

    @Test
    void previewDoesNotOccupyIdempotencySlot_sameIdCanThenReallyPublish() throws Exception {
        long base = service.currentSnapshot().version();
        // 试算用掉同一个 changeId 多次也不应占用幂等位置
        for (int i = 0; i < 3; i++) {
            PreviewResult r = service.previewChange(new ChangeRequest("same-id", "test", base,
                    Map.of("feature.checkout.percentage", "33")));
            assertTrue(r.wouldApply());
        }
        // 之后仍可用该标识真实发布成功
        ConfigSnapshot after = service.submitChange(new ChangeRequest("same-id", "test", base,
                Map.of("feature.checkout.percentage", "33")));
        assertEquals(base + 1, after.version());
        assertEquals("33", service.get("feature.checkout.percentage"));
        // 真实发布后再试算，必须明确标识为“已发布过”，不能让人误以为还能生效
        PreviewResult again = service.previewChange(new ChangeRequest("same-id", "test", after.version(),
                Map.of("feature.checkout.percentage", "33")));
        log.info("[test] 输入: 已发布 changeId 再次试算 | 依据: 幂等空间只在真实发布时占用 | 结果: wouldApply={} reason={}",
                again.wouldApply(), again.rejectionReason());
        assertFalse(again.wouldApply());
        assertEquals(RejectionReason.DUPLICATE_CHANGE_ID, again.rejectionReason());
        assertNull(again.proposedVersion());
    }

    @Test
    void stalePreview_isDistinguishableAndDoesNotChangeState() {
        long base = service.currentSnapshot().version();
        PreviewResult result = service.previewChange(new ChangeRequest("pv-stale", "test", base + 4,
                Map.of("feature.checkout.percentage", "50")));
        log.info("[test] 输入: 过期基准 baseVersion={} | 依据: 当前版本={} | 结果: reason={}",
                base + 4, service.currentSnapshot().version(), result.rejectionReason());
        assertFalse(result.wouldApply());
        assertEquals(RejectionReason.STALE_BASE_VERSION, result.rejectionReason());
        assertNull(result.proposedVersion());
        assertEquals("10", service.get("feature.checkout.percentage"));
    }

    @Test
    void invalidPreview_reportsPerItemReasonsAndWouldNotApply() {
        long base = service.currentSnapshot().version();
        Map<String, String> bad = Map.of(
                "feature.checkout.percentage", "200",
                "feature.checkout.enabled", "maybe");
        PreviewResult result = service.previewChange(new ChangeRequest("pv-bad", "test", base, bad));
        log.info("[test] 输入: 非法项 {} | 依据: 逐项校验 | 结果: reason={} codes={}",
                bad, result.rejectionReason(),
                result.errors().stream().map(e -> e.key() + ":" + e.code()).toList());
        assertFalse(result.wouldApply());
        assertEquals(RejectionReason.INVALID_ENTRIES, result.rejectionReason());
        assertNull(result.proposedVersion());
        assertTrue(result.errors().stream().anyMatch(e -> e.code() == ViolationCode.OUT_OF_RANGE));
        assertTrue(result.errors().stream().anyMatch(e -> e.code() == ViolationCode.NOT_A_BOOLEAN));
        assertNotNull(result.message());
        // 生效值不变
        assertEquals("10", service.get("feature.checkout.percentage"));
    }

    @Test
    void duplicateVsStaleVsInvalid_areClearlyDistinguishable() throws Exception {
        long base = service.currentSnapshot().version();
        service.submitChange(new ChangeRequest("real-1", "ops", base,
                Map.of("feature.checkout.percentage", "20")));
        long v = service.currentSnapshot().version();

        PreviewResult duplicate = service.previewChange(new ChangeRequest("real-1", "ops", v,
                Map.of("feature.checkout.percentage", "20")));
        PreviewResult stale = service.previewChange(new ChangeRequest("new-1", "ops", base,
                Map.of("feature.checkout.percentage", "20")));
        PreviewResult invalid = service.previewChange(new ChangeRequest("new-2", "ops", v,
                Map.of("feature.checkout.percentage", "x")));

        log.info("[test] 输入: 三类失败试算 | 依据: 原因必须可区分 | 结果: {} / {} / {}",
                duplicate.rejectionReason(), stale.rejectionReason(), invalid.rejectionReason());
        assertEquals(RejectionReason.DUPLICATE_CHANGE_ID, duplicate.rejectionReason());
        assertEquals(RejectionReason.STALE_BASE_VERSION, stale.rejectionReason());
        assertEquals(RejectionReason.INVALID_ENTRIES, invalid.rejectionReason());
    }

    @Test
    void previewThenFailingPublishLeavesEverythingUntouched() {
        long base = service.currentSnapshot().version();
        service.previewChange(new ChangeRequest("pv-x", "test", base,
                Map.of("feature.checkout.percentage", "70")));
        assertThrows(ChangeRejectedException.class, () -> service.submitChange(
                new ChangeRequest("real-x", "test", base + 1,
                        Map.of("feature.checkout.percentage", "70"))));
        assertEquals(base, service.currentSnapshot().version());
        assertEquals("10", service.get("feature.checkout.percentage"));
    }
}
