package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.VersionEvictedException;
import com.github.lisama569.featureflagrolloutjava.ops.OperationsOverview;
import com.github.lisama569.featureflagrolloutjava.ops.OperationsOverviewService;
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
 * 版本历史保留上限：超出上限的旧版本按从老到新淘汰、淘汰情况可见；
 * 回滚到已淘汰版本得到明确不可用提示，回滚到保留版本仍正常。
 */
class HistoryRetentionTest {
    private static final Logger log = LoggerFactory.getLogger(HistoryRetentionTest.class);

    private static final int RETENTION = 5;

    private ConfigService service;

    @BeforeEach
    void setUp() {
        InMemoryConfigSource defaults = new InMemoryConfigSource("defaults", 0);
        defaults.setEntries(Map.of("feature.pay.enabled", "false",
                "feature.pay.percentage", "0"));
        service = new ConfigService(List.of(defaults), new ConfigValidator(),
                new AuditLog(), RETENTION);
    }

    /** 连续发布 n 个版本（每次 percentage 加 1），返回最终版本号。 */
    private long publishVersions(int n) throws Exception {
        long v = service.currentSnapshot().version();
        for (int i = 1; i <= n; i++) {
            service.submitChange(new ChangeRequest("c-" + v + "-" + i, "test", v,
                    Map.of("feature.pay.percentage", String.valueOf(i))));
            v = service.currentSnapshot().version();
        }
        return v;
    }

    @Test
    void historyBeyondRetention_oldestVersionsEvictedAndCounted() throws Exception {
        long first = service.currentSnapshot().version(); // 1
        long last = publishVersions(9); // 版本 2..10

        log.info("[test] 输入: 连续发布到 v{}（保留上限 {}）| 依据: 超出淘汰最老 | 结果: 保留={} 淘汰={} 最早可回滚={}",
                last, RETENTION, service.history().size(),
                service.historyEvictedCount(), service.earliestRetainedVersion());
        // 历史只保留最近 RETENTION 个版本，内存不随版本数无限增长
        assertEquals(RETENTION, service.history().size());
        assertEquals(last - first + 1 - RETENTION, service.historyEvictedCount());
        assertEquals(last - RETENTION + 1, service.earliestRetainedVersion());
        // 当前版本与最近版本仍在，最老版本已被淘汰
        assertTrue(service.history().containsKey(last));
        assertFalse(service.history().containsKey(first));
        assertFalse(service.history().containsKey(first + 1));
    }

    @Test
    void rollbackToEvictedVersion_failsClearlyAndChangesNothing() throws Exception {
        long first = service.currentSnapshot().version();
        long last = publishVersions(9);
        assertTrue(service.historyEvictedCount() > 0);
        String currentValue = service.get("feature.pay.percentage");

        VersionEvictedException ex = assertThrows(VersionEvictedException.class,
                () -> service.rollback(first, "oncall"));
        log.info("[test] 输入: 回滚到已淘汰 v{} | 依据: 保留上限={} 最早可回滚={} | 结果: 明确报错={}",
                first, RETENTION, service.earliestRetainedVersion(), ex.getMessage());
        // 错误信息明确：哪个版本、为什么不可用、现在最早能回滚到哪
        assertEquals(first, ex.requestedVersion());
        assertEquals(service.earliestRetainedVersion(), ex.earliestAvailableVersion());
        assertEquals(RETENTION, ex.historyRetention());
        assertTrue(ex.getMessage().contains("淘汰"), "错误必须明确说明版本已被淘汰: " + ex.getMessage());
        // 没有静默恢复成别的内容：版本与取值完全不变
        assertEquals(last, service.currentSnapshot().version());
        assertEquals(currentValue, service.get("feature.pay.percentage"));
    }

    @Test
    void rollbackToNeverExistedVersion_isDistinguishedFromEvicted() throws Exception {
        publishVersions(2);
        long future = service.currentSnapshot().version() + 100;
        // 从未存在过的版本：不是“已淘汰”，而是“不存在”，两者必须可区分
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.rollback(future, "oncall"));
        log.info("[test] 输入: 回滚到从未存在的 v{} | 依据: 与已淘汰区分 | 结果: {}", future, ex.getMessage());
        assertTrue(ex.getMessage().contains("不存在"));
    }

    @Test
    void rollbackToRetainedVersion_stillWorksAfterEvictions() throws Exception {
        publishVersions(9);
        long earliest = service.earliestRetainedVersion();
        String expected = service.history().get(earliest).get("feature.pay.percentage");

        ConfigSnapshot rolled = service.rollback(earliest, "oncall");
        log.info("[test] 输入: 回滚到仍保留的最早版本 v{} | 依据: 未被淘汰即可回滚 | 结果: 新版本={} percentage={}",
                earliest, rolled.version(), rolled.get("feature.pay.percentage"));
        assertEquals(expected, rolled.get("feature.pay.percentage"));
        assertEquals(expected, service.get("feature.pay.percentage"));
        // 回滚本身也受保留上限约束：历史仍不超过上限
        assertTrue(service.history().size() <= RETENTION);
    }

    @Test
    void overview_showsRetentionLimitAndEvictionHint() throws Exception {
        publishVersions(9);
        OperationsOverviewService ops = new OperationsOverviewService(service);
        OperationsOverview overview = ops.overview();

        log.info("[test] 输入: 总览 | 依据: 历史保留信息 | 结果: 上限={} 保留={} 已淘汰={} 最早可回滚={}",
                overview.historyRetention(), overview.historyRetainedVersions(),
                overview.historyEvictedCount(), overview.earliestRetainedVersion());
        assertEquals(RETENTION, overview.historyRetention());
        assertEquals(RETENTION, overview.historyRetainedVersions());
        assertTrue(overview.historyEvictedCount() > 0, "总览必须能看到发生过淘汰");
        assertEquals(service.earliestRetainedVersion(), overview.earliestRetainedVersion());
    }

    @Test
    void defaultConstructor_usesDefaultRetention() {
        ConfigService defaulted = new ConfigService(
                List.of(new InMemoryConfigSource("defaults", 0)),
                new ConfigValidator(), new AuditLog());
        assertEquals(ConfigService.DEFAULT_HISTORY_RETENTION, defaulted.historyRetention());
        assertEquals(0, defaulted.historyEvictedCount());
    }
}
