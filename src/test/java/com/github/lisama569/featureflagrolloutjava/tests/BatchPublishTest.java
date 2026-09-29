package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.config.BatchPublishRequest;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.RejectionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次原子发布：整批生效/整批拒绝、只产生一个可回滚版本、回滚恢复整批内容；
 * 过期、重复、非法、空批次的拒绝原因可区分。
 */
class BatchPublishTest {
    private static final Logger log = LoggerFactory.getLogger(BatchPublishTest.class);

    private InMemoryConfigSource defaults;
    private InMemoryConfigSource dynamic;
    private AuditLog auditLog;
    private ConfigService service;

    private static BatchPublishRequest.BatchItem item(String flag, Map<String, String> entries) {
        return new BatchPublishRequest.BatchItem(flag, entries);
    }

    @BeforeEach
    void setUp() {
        defaults = new InMemoryConfigSource("defaults", 0);
        defaults.setEntries(Map.of(
                "feature.a.enabled", "false", "feature.a.percentage", "0",
                "feature.b.enabled", "false", "feature.b.percentage", "0"));
        dynamic = new InMemoryConfigSource("dynamic", 100);
        auditLog = new AuditLog();
        service = new ConfigService(List.of(defaults, dynamic), new ConfigValidator(), auditLog);
    }

    @Test
    void validBatch_appliesAtomicallyAsSingleVersionAndAuditsWholeBatch() throws Exception {
        long base = service.currentSnapshot().version();
        BatchPublishRequest batch = new BatchPublishRequest("batch-1", "release-pipeline", "alice",
                base, List.of(
                item("a", Map.of("feature.a.enabled", "true", "feature.a.percentage", "30")),
                item("b", Map.of("feature.b.enabled", "true", "feature.b.percentage", "70"))));

        ConfigSnapshot after = service.publishBatch(batch);

        log.info("[test] 输入: 双开关批次 | 依据: 一次原子切换 | 结果: 版本 {} -> {} a=30 b=70",
                base, after.version());
        // 只形成一个版本（不是每个 item 一个版本）
        assertEquals(base + 1, after.version());
        assertEquals("true", service.get("feature.a.enabled"));
        assertEquals("30", service.get("feature.a.percentage"));
        assertEquals("true", service.get("feature.b.enabled"));
        assertEquals("70", service.get("feature.b.percentage"));

        // 审计：整批内容、来源、操作者、前后版本都在同一条 APPLIED 记录里
        AuditRecord applied = auditLog.records().stream()
                .filter(r -> r.action() == AuditRecord.Action.APPLIED).reduce((x, y) -> y).orElseThrow();
        assertEquals("batch-1", applied.changeId());
        assertEquals("release-pipeline", applied.source());
        assertEquals("alice", applied.operator());
        assertEquals(base, applied.fromVersion());
        assertEquals(base + 1, applied.toVersion());
        assertTrue(applied.affectedKeys().contains("feature.a.percentage"));
        assertTrue(applied.affectedKeys().contains("feature.b.percentage"));
        assertEquals(4, applied.affectedKeys().size());
        // 一条批次记录即可完整复盘：每个键最终发布成了什么值
        assertEquals(Map.of(
                "feature.a.enabled", "true", "feature.a.percentage", "30",
                "feature.b.enabled", "true", "feature.b.percentage", "70"),
                applied.affectedEntries());
    }

    @Test
    void batchAuditRecord_carriesFullKeyValuesSourceOperatorAndVersions() throws Exception {
        long base = service.currentSnapshot().version();
        service.publishBatch(new BatchPublishRequest("batch-audit", "release-pipeline", "dave",
                base, List.of(
                item("a", Map.of("feature.a.enabled", "true", "feature.a.percentage", "45")),
                item("b", Map.of("feature.b.percentage", "5")))));

        AuditRecord record = auditLog.records().stream()
                .filter(r -> r.changeId().equals("batch-audit")
                        && r.action() == AuditRecord.Action.APPLIED)
                .findFirst().orElseThrow();
        log.info("[test] 输入: 三键批次 | 依据: 一条 APPLIED 记录承载整批 | 结果: keys={} entries={} {}->{} by {}/{}",
                record.affectedKeys(), record.affectedEntries(),
                record.fromVersion(), record.toVersion(), record.source(), record.operator());
        // 整批的键和值一一对应、与生效配置一致
        assertEquals(3, record.affectedEntries().size());
        assertEquals("true", record.affectedEntries().get("feature.a.enabled"));
        assertEquals("45", record.affectedEntries().get("feature.a.percentage"));
        assertEquals("5", record.affectedEntries().get("feature.b.percentage"));
        assertEquals(record.affectedEntries().keySet(), Set.copyOf(record.affectedKeys()));
        for (Map.Entry<String, String> e : record.affectedEntries().entrySet()) {
            assertEquals(e.getValue(), service.get(e.getKey()),
                    "审计记录的值必须与实际生效值一致: " + e.getKey());
        }
        // 来源、操作者、前后版本
        assertEquals("release-pipeline", record.source());
        assertEquals("dave", record.operator());
        assertEquals(base, record.fromVersion());
        assertEquals(base + 1, record.toVersion());
    }

    @Test
    void oneInvalidItem_rejectsWholeBatch_nothingTakesEffect() {
        long base = service.currentSnapshot().version();
        BatchPublishRequest bad = new BatchPublishRequest("batch-bad", "release", "bob", base, List.of(
                item("a", Map.of("feature.a.enabled", "true", "feature.a.percentage", "40")),
                item("b", Map.of("feature.b.enabled", "true", "feature.b.percentage", "999"))));

        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class,
                () -> service.publishBatch(bad));
        log.info("[test] 输入: 批次含 b.percentage=999 | 依据: 整批校验失败 | 结果: reason={} 版本={} a/b 均未变",
                ex.reason(), service.currentSnapshot().version());
        assertEquals(RejectionReason.INVALID_ENTRIES, ex.reason());
        // 整批保持原样：版本未前进，合法的那部分也不能先生效
        assertEquals(base, service.currentSnapshot().version());
        assertEquals("false", service.get("feature.a.enabled"));
        assertEquals("0", service.get("feature.a.percentage"));
        assertEquals("false", service.get("feature.b.enabled"));
        assertEquals("0", service.get("feature.b.percentage"));
        assertTrue(auditLog.records().stream().anyMatch(r -> r.action() == AuditRecord.Action.REJECTED
                && r.changeId().equals("batch-bad")));
    }

    @Test
    void staleBase_rejectsWholeBatchWithoutPartialEffect() throws Exception {
        long base = service.currentSnapshot().version();
        // 先制造一次真实发布使 base 过期
        service.publishBatch(new BatchPublishRequest("other", "release", "bob", base,
                List.of(item("a", Map.of("feature.a.percentage", "10")))));
        long current = service.currentSnapshot().version();

        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class,
                () -> service.publishBatch(new BatchPublishRequest("batch-stale", "release", "bob", base,
                        List.of(item("b", Map.of("feature.b.percentage", "50"))))));
        log.info("[test] 输入: 过期批次 base={} 当前={} | 依据: 乐观并发 | 结果: reason={}",
                base, current, ex.reason());
        assertEquals(RejectionReason.STALE_BASE_VERSION, ex.reason());
        assertEquals("0", service.get("feature.b.percentage"));
    }

    @Test
    void duplicateBatchId_isRejectedAndIdempotentSpaceSharedWithSingleChange() throws Exception {
        long base = service.currentSnapshot().version();
        BatchPublishRequest first = new BatchPublishRequest("shared-id", "release", "bob", base,
                List.of(item("a", Map.of("feature.a.percentage", "15"))));
        service.publishBatch(first);
        long v = service.currentSnapshot().version();

        // 同 batchId 再发一次（即使基准更新、内容不同）也必须拒绝
        ChangeRejectedException dup = assertThrows(ChangeRejectedException.class,
                () -> service.publishBatch(new BatchPublishRequest("shared-id", "release", "bob", v,
                        List.of(item("b", Map.of("feature.b.percentage", "80"))))));
        assertEquals(RejectionReason.DUPLICATE_CHANGE_ID, dup.reason());
        log.info("[test] 输入: 重复 batchId=shared-id | 依据: 幂等 | 结果: reason={} b.percentage={}",
                dup.reason(), service.get("feature.b.percentage"));
        assertEquals("0", service.get("feature.b.percentage"));
    }

    @Test
    void emptyBatch_rejectedWithDistinctReason() {
        long base = service.currentSnapshot().version();
        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class,
                () -> service.publishBatch(new BatchPublishRequest("empty", "release", "bob",
                        base, List.of())));
        log.info("[test] 输入: 空批次 | 依据: 没有可发布项 | 结果: reason={}", ex.reason());
        assertEquals(RejectionReason.EMPTY_BATCH, ex.reason());
        assertEquals(base, service.currentSnapshot().version());
    }

    @Test
    void rollbackToBeforeBatch_restoresWholeBatchContent() throws Exception {
        long beforeVersion = service.currentSnapshot().version();
        assertEquals("0", service.get("feature.a.percentage"));
        assertEquals("0", service.get("feature.b.percentage"));

        long afterVersion = service.publishBatch(new BatchPublishRequest("batch-rb", "release", "carol",
                beforeVersion, List.of(
                item("a", Map.of("feature.a.enabled", "true", "feature.a.percentage", "30")),
                item("b", Map.of("feature.b.enabled", "true", "feature.b.percentage", "70"))))).version();
        assertEquals(afterVersion, beforeVersion + 1);
        assertEquals("70", service.get("feature.b.percentage"));

        // 回滚到批次前版本：整批内容一起恢复，不能只回一部分
        ConfigSnapshot rolled = service.rollback(beforeVersion, "oncall");
        log.info("[test] 输入: 回滚到批次前 v{} | 依据: 历史快照为整批前内容 | 结果: 新版本={} a.pct={} b.pct={} a.en={}",
                beforeVersion, rolled.version(),
                rolled.get("feature.a.percentage"), rolled.get("feature.b.percentage"),
                rolled.get("feature.a.enabled"));
        assertEquals(afterVersion + 1, rolled.version());
        assertEquals("0", rolled.get("feature.a.percentage"));
        assertEquals("0", rolled.get("feature.b.percentage"));
        assertEquals("false", rolled.get("feature.a.enabled"));
        assertEquals("false", rolled.get("feature.b.enabled"));

        AuditRecord rollbackRecord = auditLog.records().stream()
                .filter(r -> r.action() == AuditRecord.Action.ROLLED_BACK)
                .reduce((x, y) -> y).orElseThrow();
        assertEquals("oncall", rollbackRecord.operator());
        assertEquals(afterVersion, rollbackRecord.fromVersion());
    }
}
