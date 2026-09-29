package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.HistoryRetentionStatus;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.VersionEvictedException;
import com.github.lisama569.featureflagrolloutjava.config.VersionNotFoundException;
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
 * 版本历史保留上限：超出按 FIFO 淘汰最老版本、内存有界；总览/查询可见淘汰提示；
 * 回滚到已淘汰版本必须明确报“已被淘汰、不可用”，绝不静默恢复成别的内容；
 * 回滚到从未存在的版本与被淘汰的版本可区分。
 */
class HistoryRetentionTest {
    private static final Logger log = LoggerFactory.getLogger(HistoryRetentionTest.class);

    private static final int LIMIT = 3;
    private ConfigService service;

    private void publish(String id, long base, String pct) throws Exception {
        service.submitChange(new ChangeRequest(id, "ops", base,
                Map.of("feature.pay.percentage", pct)));
    }

    @BeforeEach
    void setUp() {
        InMemoryConfigSource defaults = new InMemoryConfigSource("defaults", 0);
        defaults.setEntries(Map.of(
                "feature.pay.enabled", "false", "feature.pay.percentage", "0"));
        service = new ConfigService(List.of(defaults, new InMemoryConfigSource("dynamic", 100)),
                new ConfigValidator(), new AuditLog(), LIMIT);
    }

    @Test
    void historyIsBounded_oldVersionsEvictedInFifoOrder_statusReportsIt() throws Exception {
        // 构造器 refresh 产生 v1，历史 [v0,v1]
        long v1 = service.currentSnapshot().version();
        assertEquals(1, v1);
        publish("c1", v1, "10");   // -> v2，历史 [v0,v1,v2]
        publish("c2", 2, "20");    // -> v3，超过上限 3，淘汰 v0；历史 [v1,v2,v3]
        publish("c3", 3, "30");    // -> v4，淘汰 v1；历史 [v2,v3,v4]

        HistoryRetentionStatus st = service.historyRetentionStatus();
        log.info("[test] 保留上限={} 保留={} 淘汰={} 最近淘汰={}",
                st.retentionLimit(), st.retainedVersions(),
                st.evictedVersionCount(), st.recentlyEvictedVersions());

        assertEquals(LIMIT, st.retentionLimit());
        assertEquals(LIMIT, st.retainedCount());
        assertEquals(List.of(2L, 3L, 4L), st.retainedVersions());
        assertEquals(2L, st.oldestRetainedVersion());
        assertEquals(2, st.evictedVersionCount());
        assertTrue(st.recentlyEvictedVersions().contains(0L));
        assertTrue(st.recentlyEvictedVersions().contains(1L));

        // 保留中的版本仍可取，被淘汰的版本不再保留快照
        assertTrue(service.history().containsKey(2L));
        assertFalse(service.history().containsKey(0L));
        assertFalse(service.history().containsKey(1L));
        assertEquals(3, service.history().size(), "历史内存有界，不随版本数无限增长");
    }

    @Test
    void rollbackToEvictedVersion_failsWithClearError_andNeverSilentlyRestores() throws Exception {
        long v1 = service.currentSnapshot().version();
        publish("c1", v1, "10");   // v2
        publish("c2", 2, "20");    // v3（淘汰 v0）
        publish("c3", 3, "30");    // v4（淘汰 v1），历史 [v2,v3,v4]
        long currentBefore = service.currentSnapshot().version();
        assertEquals("30", service.get("feature.pay.percentage"));

        // 回滚到被淘汰的 v1：必须明确报错，且当前值原封不动
        VersionEvictedException ex = assertThrows(VersionEvictedException.class,
                () -> service.rollback(1, "oncall"));
        log.info("[test] 回滚已淘汰 v1 -> reason 消息: {}", ex.getMessage());
        assertEquals(1, ex.version());
        assertEquals(2, ex.oldestRetainedVersion());
        assertEquals(LIMIT, ex.retentionLimit());
        assertTrue(ex.getMessage().contains("淘汰"));
        // 没有静默恢复：版本未前进，值仍是 30
        assertEquals(currentBefore, service.currentSnapshot().version());
        assertEquals("30", service.get("feature.pay.percentage"));
    }

    @Test
    void rollbackToNeverExistentVersion_isDistinctFromEvicted() {
        VersionNotFoundException ex = assertThrows(VersionNotFoundException.class,
                () -> service.rollback(999, "oncall"));
        log.info("[test] 回滚从未存在 v999 -> {}", ex.getMessage());
        assertEquals(999, ex.version());
        assertTrue(ex.getMessage().contains("从未存在"));
    }

    @Test
    void rollbackToRetainedVersionStillWorks_andRolledBackVersionIsKeptInWindow() throws Exception {
        long v1 = service.currentSnapshot().version();
        publish("c1", v1, "10");   // v2
        publish("c2", 2, "20");    // v3（淘汰 v0）历史 [v1,v2,v3]

        // v1 仍在窗口内，可回滚；回滚产生 v4 并淘汰 v1（FIFO）
        var rolled = service.rollback(1, "oncall");
        assertEquals(4, rolled.version());
        assertEquals("0", rolled.get("feature.pay.percentage"), "内容取自仍保留的 v1");

        HistoryRetentionStatus st = service.historyRetentionStatus();
        assertEquals(List.of(2L, 3L, 4L), st.retainedVersions());
        // 刚回滚完，v1 已被淘汰，再回滚 v1 要明确报错
        assertThrows(VersionEvictedException.class, () -> service.rollback(1, "oncall"));
        // 但回滚产生的新版本 v4 与窗口内的 v2/v3 仍可回滚
        assertEquals("10", service.rollback(2, "oncall").get("feature.pay.percentage"));
        log.info("[test] 窗口内版本仍可回滚，淘汰后明确拒绝，状态={}", st.retainedVersions());
    }

    @Test
    void operationsOverview_surfacesHistoryRetentionAndEvictionHint() throws Exception {
        com.github.lisama569.featureflagrolloutjava.ops.OperationsOverviewService ops =
                new com.github.lisama569.featureflagrolloutjava.ops.OperationsOverviewService(service);
        long v1 = service.currentSnapshot().version();
        publish("c1", v1, "10");   // v2
        publish("c2", 2, "20");    // v3 淘汰 v0
        publish("c3", 3, "30");    // v4 淘汰 v1

        var overview = ops.overview();
        HistoryRetentionStatus h = overview.history();
        log.info("[test] 总览历史提示: 上限={} 保留={} 淘汰={}",
                h.retentionLimit(), h.retainedVersions(), h.evictedVersionCount());
        assertEquals(LIMIT, h.retentionLimit());
        assertEquals(2, h.evictedVersionCount(), "总览必须能看到已淘汰版本数");
        assertEquals(2L, h.oldestRetainedVersion());
        assertTrue(h.recentlyEvictedVersions().contains(0L));
        assertTrue(h.recentlyEvictedVersions().contains(1L));
    }
}
