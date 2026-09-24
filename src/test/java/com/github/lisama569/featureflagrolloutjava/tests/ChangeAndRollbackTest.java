package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.audit.AuditLog;
import com.github.lisama569.featureflagrolloutjava.audit.InMemoryAuditLog;
import com.github.lisama569.featureflagrolloutjava.audit.InMemorySnapshotHistory;
import com.github.lisama569.featureflagrolloutjava.domain.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry;
import com.github.lisama569.featureflagrolloutjava.domain.FlagEntry;
import com.github.lisama569.featureflagrolloutjava.domain.MergedConfig;
import com.github.lisama569.featureflagrolloutjava.domain.PropertyEntry;
import com.github.lisama569.featureflagrolloutjava.domain.RejectReason;
import com.github.lisama569.featureflagrolloutjava.domain.SourceSnapshot;
import com.github.lisama569.featureflagrolloutjava.merge.PriorityConfigMerger;
import com.github.lisama569.featureflagrolloutjava.rollout.FnvBucketer;
import com.github.lisama569.featureflagrolloutjava.service.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.service.FeatureFlagService;
import com.github.lisama569.featureflagrolloutjava.source.MapConfigSource;
import com.github.lisama569.featureflagrolloutjava.validation.StrictConfigValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 核心服务测试（二）：过期变更、重复提交、无变化提交、非法提交必须被拒绝且原因可区分；
 * 接受路径更新版本并留审计；回滚恢复历史版本。
 */
class ChangeAndRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(ChangeAndRollbackTest.class);

    private MapConfigSource remote;
    private FeatureFlagService service;
    private AuditLog auditLog;

    @BeforeEach
    void setUp() {
        MapConfigSource local = new MapConfigSource("local-defaults", 0,
                List.of(new PropertyEntry("endpoint", "http://local")));
        remote = new MapConfigSource("remote", 100,
                List.of(new PropertyEntry("endpoint", "http://remote-v1")));
        auditLog = new InMemoryAuditLog();
        service = new FeatureFlagService(
                List.of(local, remote),
                new StrictConfigValidator(),
                new PriorityConfigMerger(),
                new FnvBucketer(),
                auditLog,
                new InMemorySnapshotHistory());
        service.reload();
    }

    private ChangeRequest request(String id, long expectedVersion, List<ConfigEntry> entries) {
        return new ChangeRequest(id, "remote", expectedVersion, entries);
    }

    @Test
    void validSubmitIsAcceptedAndPublishesNewVersion() {
        long before = service.current().version();
        var req = request("req-1", 0, List.of(new PropertyEntry("endpoint", "http://remote-v2")));
        MergedConfig after = service.submit(req);
        log.info("输入: {} | 判定依据: 期望版本0==当前0且内容有变化 | 最终生效: endpoint={}, config v{}→v{}",
                req, service.getProperty("endpoint").orElseThrow(), before, after.version());
        assertEquals("http://remote-v2", service.getProperty("endpoint").orElseThrow());
        assertEquals(before + 1, after.version());
        assertEquals(1, remote.load().sourceVersion());
    }

    @Test
    void staleSubmitMustBeRejectedWithStaleVersion() {
        service.submit(request("req-1", 0, List.of(new PropertyEntry("endpoint", "http://remote-v2"))));
        // 第二人仍基于 v0 提交
        var stale = request("req-2", 0, List.of(new PropertyEntry("endpoint", "http://remote-v3")));
        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class, () -> service.submit(stale));
        log.info("输入: {} | 判定依据: 期望版本0 != 当前版本1 | 最终生效: 拒绝原因={}, endpoint仍为={}",
                stale, ex.reason(), service.getProperty("endpoint").orElseThrow());
        assertEquals(RejectReason.STALE_VERSION, ex.reason());
        assertEquals("http://remote-v2", service.getProperty("endpoint").orElseThrow(),
                "过期变更不得覆盖当前生效值");
    }

    @Test
    void duplicateSubmitMustBeRejectedWithDuplicateSubmission() {
        var req = request("dup-id", 0, List.of(new PropertyEntry("endpoint", "http://remote-v2")));
        service.submit(req);
        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class, () -> service.submit(req));
        log.info("输入: 同一 requestId=dup-id 再次提交 | 判定依据: requestId 已处理 | 最终生效: 拒绝原因={}",
                ex.reason());
        assertEquals(RejectReason.DUPLICATE_SUBMISSION, ex.reason());
        assertEquals("http://remote-v2", service.getProperty("endpoint").orElseThrow());
    }

    @Test
    void noOpSubmitMustBeRejectedWithNoOpChange() {
        var req = request("noop-id", 0, List.of(new PropertyEntry("endpoint", "http://remote-v1")));
        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class, () -> service.submit(req));
        log.info("输入: 内容与当前完全一致 | 判定依据: 条目列表相等 | 最终生效: 拒绝原因={}", ex.reason());
        assertEquals(RejectReason.NO_OP_CHANGE, ex.reason());
    }

    @Test
    void invalidSubmitMustBeRejectedWithValidationReasonAndNotPublished() {
        long before = service.current().version();
        var req = request("bad-id", 0,
                List.<ConfigEntry>of(new FlagEntry("f", true, 500, false, "exp")));
        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class, () -> service.submit(req));
        log.info("输入: 放量比例500 | 判定依据: 超出[0,100] | 最终生效: 拒绝原因={}, config版本保持 v{}",
                ex.reason(), service.current().version());
        assertEquals(RejectReason.INVALID_FLAG_PERCENTAGE, ex.reason());
        assertEquals(before, service.current().version(), "非法提交不得产生新版本");
    }

    @Test
    void unknownSourceAndReusedIdAfterRejection() {
        var unknown = new ChangeRequest("x", "nope", 0, List.of());
        ChangeRejectedException ex = assertThrows(ChangeRejectedException.class,
                () -> service.submit(unknown));
        assertEquals(RejectReason.UNKNOWN_SOURCE, ex.reason());
        log.info("输入: sourceName=nope | 判定依据: 来源不存在 | 最终生效: 拒绝原因={}", ex.reason());

        // 非法提交被拒后，其 requestId 不应被"吞掉"：同样的合法重试可再次使用（不判为重复）
        var badThenFixed = request("retry-id", 0,
                List.<ConfigEntry>of(new PropertyEntry("endpoint", "")));
        var badEx = assertThrows(ChangeRejectedException.class, () -> service.submit(badThenFixed));
        assertEquals(RejectReason.INVALID_PROPERTY_BLANK, badEx.reason());
        var fixed = request("retry-id", 0,
                List.of(new PropertyEntry("endpoint", "http://remote-fixed")));
        MergedConfig after = service.submit(fixed);
        log.info("输入: 同一 requestId 先非法被拒、后合法重试 | 判定依据: 拒绝时释放 requestId | 最终生效: endpoint={}, v{}",
                service.getProperty("endpoint").orElseThrow(), after.version());
        assertEquals("http://remote-fixed", service.getProperty("endpoint").orElseThrow());
    }

    @Test
    void rollbackRestoresHistoricalStateAndKeepsAuditTrail() {
        long v0 = service.current().version();
        service.submit(request("r1", 0, List.of(new PropertyEntry("endpoint", "http://v2"))));
        service.submit(request("r2", 1, List.of(new PropertyEntry("endpoint", "http://v3"))));
        assertEquals("http://v3", service.getProperty("endpoint").orElseThrow());

        MergedConfig rolled = service.rollback(v0);
        log.info("输入: 回滚到 v{} | 判定依据: 恢复该版本各来源快照后重新合并 | 最终生效: endpoint={}, 新版本=v{}",
                v0, service.getProperty("endpoint").orElseThrow(), rolled.version());
        assertEquals("http://remote-v1", service.getProperty("endpoint").orElseThrow(),
                "回滚后应恢复 v0 当时的来源内容（remote 初始值）");

        long accepted = auditLog.all().stream()
                .filter(a -> a.kind().name().equals("SUBMIT_ACCEPTED")).count();
        long rollbacks = auditLog.all().stream().filter(a -> a.kind().name().equals("ROLLBACK")).count();
        log.info("审计统计: SUBMIT_ACCEPTED={} 条, ROLLBACK={} 条, 总记录={} 条",
                accepted, rollbacks, auditLog.all().size());
        assertEquals(2, accepted);
        assertEquals(1, rollbacks);
    }
}
