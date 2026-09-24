package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.RejectionReason;
import com.github.lisama569.featureflagrolloutjava.config.ViolationCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigServiceTest {
    private static final Logger log = LoggerFactory.getLogger(ConfigServiceTest.class);

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
    void priorityMerge_highPriorityWins() {
        dynamic.setEntries(Map.of("feature.checkout.percentage", "50"));
        ConfigSnapshot snap = service.refresh();
        log.info("[test] 输入: defaults.percentage=10, dynamic.percentage=50 | 依据: dynamic 优先级 100 > defaults 0 | 结果: {}",
                snap.get("feature.checkout.percentage"));
        assertEquals("50", snap.get("feature.checkout.percentage"));
        assertEquals("false", snap.get("feature.checkout.enabled"));
    }

    @Test
    void invalidHighPriorityEntry_doesNotOverrideEffectiveValue() {
        dynamic.setEntries(Map.of("feature.checkout.percentage", "not-a-number"));
        ConfigSnapshot snap = service.refresh();
        log.info("[test] 输入: dynamic.percentage=not-a-number(非法) | 依据: 高优先级非法项被拒绝，保留低优先级生效值 | 结果: {}",
                snap.get("feature.checkout.percentage"));
        assertEquals("10", snap.get("feature.checkout.percentage"));
    }

    @Test
    void invalidChange_rejectedWithDistinguishableReasons() {
        long base = service.currentSnapshot().version();
        Map<String, String> bad = Map.of(
                "feature.a.percentage", "abc",
                "feature.b.percentage", "200",
                "feature.c.enabled", "yes");
        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class,
                () -> service.submitChange(new ChangeRequest("c-bad", "test", base, bad)));
        log.info("[test] 输入: {} | 依据: 校验失败码={} | 结果: 拒绝原因={}",
                bad, ex.errors().stream().map(e -> e.key() + ":" + e.code()).toList(), ex.reason());
        assertEquals(RejectionReason.INVALID_ENTRIES, ex.reason());
        assertTrue(ex.errors().stream().anyMatch(e -> e.code() == ViolationCode.NOT_AN_INTEGER));
        assertTrue(ex.errors().stream().anyMatch(e -> e.code() == ViolationCode.OUT_OF_RANGE));
        assertTrue(ex.errors().stream().anyMatch(e -> e.code() == ViolationCode.NOT_A_BOOLEAN));
        // 生效值未被污染
        assertEquals("10", service.get("feature.checkout.percentage"));
    }

    @Test
    void staleChange_rejectedAndDoesNotOverwrite() throws Exception {
        long base = service.currentSnapshot().version();
        service.submitChange(new ChangeRequest("c1", "ops", base, Map.of("feature.checkout.percentage", "30")));
        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class,
                () -> service.submitChange(new ChangeRequest("c2", "ops", base,
                        Map.of("feature.checkout.percentage", "99"))));
        log.info("[test] 输入: baseVersion={} 当前版本={} | 依据: baseVersion 过期 | 结果: 拒绝原因={} 生效值={}",
                base, service.currentSnapshot().version(), ex.reason(), service.get("feature.checkout.percentage"));
        assertEquals(RejectionReason.STALE_BASE_VERSION, ex.reason());
        assertEquals("30", service.get("feature.checkout.percentage"));
    }

    @Test
    void duplicateChange_rejected() throws Exception {
        long base = service.currentSnapshot().version();
        service.submitChange(new ChangeRequest("c1", "ops", base, Map.of("feature.checkout.percentage", "30")));
        long afterFirst = service.currentSnapshot().version();
        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class,
                () -> service.submitChange(new ChangeRequest("c1", "ops", afterFirst,
                        Map.of("feature.checkout.percentage", "80"))));
        log.info("[test] 输入: 重复 changeId=c1 | 依据: changeId 已应用 | 结果: 拒绝原因={} 生效值={}",
                ex.reason(), service.get("feature.checkout.percentage"));
        assertEquals(RejectionReason.DUPLICATE_CHANGE_ID, ex.reason());
        assertEquals("30", service.get("feature.checkout.percentage"));
    }

    @Test
    void sourceUnavailable_fallsBackToLastGoodSnapshot() {
        dynamic.setEntries(Map.of("feature.checkout.percentage", "70"));
        service.refresh();
        assertEquals("70", service.get("feature.checkout.percentage"));

        dynamic.setAvailable(false);
        dynamic.setEntries(Map.of("feature.checkout.percentage", "5"));
        ConfigSnapshot snap = service.refresh();
        log.info("[test] 输入: dynamic 来源不可用 | 依据: 降级到最近一次可用快照(percentage=70) | 结果: {}",
                snap.get("feature.checkout.percentage"));
        assertEquals("70", snap.get("feature.checkout.percentage"));
    }

    @Test
    void rollback_restoresPreviousVersionAndAudits() throws Exception {
        long v1 = service.currentSnapshot().version();
        service.submitChange(new ChangeRequest("c1", "ops", v1,
                Map.of("feature.checkout.percentage", "90")));
        assertEquals("90", service.get("feature.checkout.percentage"));

        ConfigSnapshot rolled = service.rollback(v1, "oncall");
        log.info("[test] 输入: rollback to v{} | 依据: 历史快照存在 | 结果: 新版本={} percentage={}",
                v1, rolled.version(), rolled.get("feature.checkout.percentage"));
        assertEquals("10", rolled.get("feature.checkout.percentage"));

        List<AuditRecord> records = auditLog.records();
        assertTrue(records.stream().anyMatch(r -> r.action() == AuditRecord.Action.APPLIED
                && r.source().equals("ops") && r.toVersion() == v1 + 1));
        assertTrue(records.stream().anyMatch(r -> r.action() == AuditRecord.Action.ROLLED_BACK
                && r.source().equals("oncall")));
    }

    @Test
    void rejectedChanges_areAudited() {
        long base = service.currentSnapshot().version();
        assertThrows(ChangeRejectedException.class, () -> service.submitChange(
                new ChangeRequest("c-x", "ops", base + 5, Map.of("k", "v"))));
        List<AuditRecord> records = auditLog.records();
        log.info("[test] 输入: 过期变更 c-x | 依据: 审计记录={} | 结果: 含 REJECTED 记录", records);
        assertTrue(records.stream().anyMatch(r -> r.action() == AuditRecord.Action.REJECTED
                && r.detail().equals(RejectionReason.STALE_BASE_VERSION.name())));
    }
}
