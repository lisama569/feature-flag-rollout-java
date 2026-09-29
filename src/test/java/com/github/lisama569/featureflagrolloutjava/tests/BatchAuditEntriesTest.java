package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.config.BatchPublishRequest;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一条批次审计记录里必须能完整读到整批的键和最终发布值、来源、操作者、发布前后版本；
 * 整批回滚后，回滚审计同样携带恢复后的完整键值。
 */
class BatchAuditEntriesTest {
    private static final Logger log = LoggerFactory.getLogger(BatchAuditEntriesTest.class);

    private AuditLog auditLog;
    private ConfigService service;
    private long base;

    @BeforeEach
    void setUp() {
        InMemoryConfigSource defaults = new InMemoryConfigSource("defaults", 0);
        defaults.setEntries(Map.of(
                "feature.a.enabled", "false", "feature.a.percentage", "0",
                "feature.b.enabled", "false", "feature.b.percentage", "0"));
        auditLog = new AuditLog();
        service = new ConfigService(List.of(defaults, new InMemoryConfigSource("dynamic", 100)),
                new ConfigValidator(), auditLog);
        base = service.currentSnapshot().version();
    }

    @Test
    void appliedBatchAuditRecord_containsEveryKeyAndFinalValue_sourceOperatorVersions() throws Exception {
        BatchPublishRequest batch = new BatchPublishRequest("rel-42", "release-pipeline", "alice",
                base, List.of(
                new BatchPublishRequest.BatchItem("a", Map.of(
                        "feature.a.enabled", "true", "feature.a.percentage", "30")),
                new BatchPublishRequest.BatchItem("b", Map.of(
                        "feature.b.enabled", "true", "feature.b.percentage", "70"))));
        long afterVersion = service.publishBatch(batch).version();

        AuditRecord rec = auditLog.records().stream()
                .filter(r -> r.action() == AuditRecord.Action.APPLIED
                        && "rel-42".equals(r.changeId()))
                .reduce((x, y) -> y).orElseThrow();

        log.info("[test] 批次审计: source={} operator={} {}->{} keys={} entries={}",
                rec.source(), rec.operator(), rec.fromVersion(), rec.toVersion(),
                rec.affectedKeys(), rec.entries());

        assertEquals("release-pipeline", rec.source());
        assertEquals("alice", rec.operator());
        assertEquals(base, rec.fromVersion());
        assertEquals(afterVersion, rec.toVersion());
        // 整批四个键都在
        assertEquals(4, rec.affectedKeys().size());
        // 每个键的最终发布值都完整可读（复盘追责不必再猜）
        assertEquals(4, rec.entries().size());
        assertEquals("true", rec.entries().get("feature.a.enabled"));
        assertEquals("30", rec.entries().get("feature.a.percentage"));
        assertEquals("true", rec.entries().get("feature.b.enabled"));
        assertEquals("70", rec.entries().get("feature.b.percentage"));
    }

    @Test
    void rollbackAuditRecord_containsWholeRestoredBatchValues() throws Exception {
        long afterVersion = service.publishBatch(new BatchPublishRequest("rel-43", "pipeline", "bob",
                base, List.of(
                new BatchPublishRequest.BatchItem("a", Map.of(
                        "feature.a.enabled", "true", "feature.a.percentage", "30")),
                new BatchPublishRequest.BatchItem("b", Map.of(
                        "feature.b.enabled", "true", "feature.b.percentage", "70"))))).version();

        service.rollback(base, "oncall");

        AuditRecord rb = auditLog.records().stream()
                .filter(r -> r.action() == AuditRecord.Action.ROLLED_BACK)
                .reduce((x, y) -> y).orElseThrow();
        log.info("[test] 回滚审计: operator={} {}->{} entries={}",
                rb.operator(), rb.fromVersion(), rb.toVersion(), rb.entries());

        assertEquals("oncall", rb.operator());
        assertEquals(afterVersion, rb.fromVersion());
        // 回滚恢复的是整批内容：四个键都回到批次前的值
        assertEquals("false", rb.entries().get("feature.a.enabled"));
        assertEquals("0", rb.entries().get("feature.a.percentage"));
        assertEquals("false", rb.entries().get("feature.b.enabled"));
        assertEquals("0", rb.entries().get("feature.b.percentage"));
        assertTrue(rb.affectedKeys().containsAll(rb.entries().keySet()));
    }

    @Test
    void rejectedBatchAuditRecord_doesNotCarryPublishedValues() {
        assertThrows(ChangeRejectedException.class,
                () -> service.publishBatch(new BatchPublishRequest("rel-bad", "pipeline", "bob", base, List.of(
                new BatchPublishRequest.BatchItem("a", Map.of("feature.a.percentage", "40")),
                new BatchPublishRequest.BatchItem("b", Map.of("feature.b.percentage", "999"))))));
        // 非法整批拒绝 -> 不应有 APPLIED 记录，REJECTED 记录不携带发布值
        boolean hasApplied = auditLog.records().stream()
                .anyMatch(r -> r.action() == AuditRecord.Action.APPLIED && "rel-bad".equals(r.changeId()));
        AuditRecord rejected = auditLog.records().stream()
                .filter(r -> r.action() == AuditRecord.Action.REJECTED && "rel-bad".equals(r.changeId()))
                .reduce((x, y) -> y).orElseThrow();
        assertTrue(rejected.entries().isEmpty(), "被拒绝的批次不应记录为已发布键值");
        assertFalse(hasApplied);
        log.info("[test] 拒绝批次审计 entries 为空: {}", rejected.entries());
    }
}
