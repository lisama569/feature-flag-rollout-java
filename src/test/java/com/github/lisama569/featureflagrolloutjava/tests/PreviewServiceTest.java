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

    @Test
    void validPreview_returnsFullMergedConfigComparableWithCurrent() {
        long base = service.currentSnapshot().version();
        PreviewResult result = service.previewChange(new ChangeRequest("pv-full", "test", base,
                Map.of("feature.checkout.enabled", "true",
                        "feature.checkout.percentage", "60",
                        "feature.fresh.enabled", "true")));

        // 合并后的完整预计配置：未涉及的键保持当前值，提交的键取新值
        assertNotNull(result.mergedEntries());
        assertEquals(service.currentSnapshot().entries().size() + 1,
                result.mergedEntries().size(), "新增一个键，其余与当前一致");
        assertEquals("true", result.mergedEntries().get("feature.checkout.enabled"));
        assertEquals("60", result.mergedEntries().get("feature.checkout.percentage"));
        assertEquals("true", result.mergedEntries().get("feature.fresh.enabled"));
        // 可与当前值逐项对照：提交涉及的键与 diffs 一致，未涉及的键与当前快照一致
        for (Map.Entry<String, String> e : service.currentSnapshot().entries().entrySet()) {
            String expected = switch (e.getKey()) {
                case "feature.checkout.enabled" -> "true";
                case "feature.checkout.percentage" -> "60";
                default -> e.getValue();
            };
            assertEquals(expected, result.mergedEntries().get(e.getKey()),
                    "合并结果与当前值对照不一致: " + e.getKey());
        }
        log.info("[test] 输入: 合法试算 | 依据: 合并后完整配置 | 结果: mergedEntries={} 项，可与当前快照逐项对照",
                result.mergedEntries().size());
    }

    @Test
    void rejectedPreviews_neverCarryWouldBeValues_andNeverChangeState() throws Exception {
        long base = service.currentSnapshot().version();
        // 先真实发布一次，制造“已发布过”的标识
        service.submitChange(new ChangeRequest("real-dup", "ops", base,
                Map.of("feature.checkout.percentage", "20")));
        long current = service.currentSnapshot().version();
        int auditBefore = auditLog.records().size();

        Map<String, String> wouldBe = Map.of(
                "feature.checkout.enabled", "true",
                "feature.checkout.percentage", "99",
                "feature.brand-new.enabled", "true");
        PreviewResult stale = service.previewChange(new ChangeRequest("pv-s", "test", base, wouldBe));
        PreviewResult invalid = service.previewChange(new ChangeRequest("pv-i", "test", current,
                Map.of("feature.checkout.percentage", "999",
                        "feature.brand-new.enabled", "true")));
        PreviewResult duplicate = service.previewChange(new ChangeRequest("real-dup", "ops", current, wouldBe));

        for (PreviewResult r : List.of(stale, invalid, duplicate)) {
            assertFalse(r.wouldApply());
            assertNull(r.proposedVersion(), "被拒试算不得给出预计版本");
            assertNull(r.mergedEntries(), "被拒试算不得回带合并后的预计配置");
            assertTrue(r.diffs().isEmpty(), "被拒试算不得把不会生效的值当作预计值返回");
            assertTrue(r.affectedFlags().isEmpty(), "被拒试算不得把不会生效的开关当作受影响项返回");
            assertNotNull(r.rejectionReason());
            assertNotNull(r.message());
        }
        // 非法试算仍给出每项可区分的校验明细（这不是“预计值”，是拒绝依据）
        assertFalse(invalid.errors().isEmpty());
        assertTrue(invalid.errors().stream().anyMatch(e -> e.code() == ViolationCode.OUT_OF_RANGE));

        // 三类被拒试算都不能改动任何状态：生效配置、可回滚历史、审计、幂等占位
        assertEquals(current, service.currentSnapshot().version());
        assertEquals("false", service.get("feature.checkout.enabled"));
        assertEquals("20", service.get("feature.checkout.percentage"));
        assertNull(service.get("feature.brand-new.enabled"));
        assertEquals(auditBefore, auditLog.records().size());
        assertFalse(service.history().containsKey(current + 1));
        // 试算用过的标识没有占用幂等位置，仍可真实发布
        ConfigSnapshot after = service.submitChange(new ChangeRequest("pv-s", "test", current,
                Map.of("feature.checkout.percentage", "30")));
        assertEquals(current + 1, after.version());
        log.info("[test] 输入: 过期/非法/重复三类被拒试算 | 依据: 不生效即不回带预计值 | 结果: 三类均无预计值且状态未变，pv-s 随后可真实发布到 v{}",
                after.version());
    }
}
