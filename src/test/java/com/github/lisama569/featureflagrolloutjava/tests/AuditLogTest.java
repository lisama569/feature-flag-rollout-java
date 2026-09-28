package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.AuditPage;
import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 审计日志容量上限、截断提示与按时间/结果筛选。 */
class AuditLogTest {
    private static final Logger log = LoggerFactory.getLogger(AuditLogTest.class);

    @Test
    void boundedCapacity_evictsOldestAndReportsDropped() {
        AuditLog log = new AuditLog(10);
        for (int i = 0; i < 25; i++) {
            log.append("c" + i, "src", null, i, i + 1,
                    i % 3 == 0 ? AuditRecord.Action.REJECTED : AuditRecord.Action.APPLIED,
                    "detail-" + i);
        }
        AuditPage page = log.query(null, null, null, 100);
        assertEquals(10, page.returned(), "缓冲容量 10，最多保留 10 条");
        assertEquals(10, page.totalMatched());
        assertEquals(15, page.droppedTotal(), "25-10=15 条更老记录被挤出");
        assertEquals("c15", page.records().get(page.records().size() - 1).changeId(),
                "保留的应是最新 10 条(c15..c24)");
        assertEquals("c24", page.records().get(0).changeId(), "结果按时间倒序，最新在前");
        AuditLogTest.log.info("[test] 输入: 容量10 写入25 | 结果: 保留={} 丢弃={} 倒序首条={}",
                page.returned(), page.droppedTotal(), page.records().get(0).changeId());
    }

    @Test
    void query_limitTruncationAndActionFilter() {
        AuditLog log = new AuditLog(100);
        for (int i = 0; i < 6; i++) {
            log.append("a" + i, "src", null, i, i + 1, AuditRecord.Action.APPLIED, "d");
        }
        log.append("r1", "src", null, 0, 0, AuditRecord.Action.REJECTED, "rej");
        log.append("rb1", "op", "oncall", 1, 2, AuditRecord.Action.ROLLED_BACK, "rollback");

        AuditPage limited = log.query(null, null, null, 3);
        assertEquals(3, limited.returned());
        assertTrue(limited.truncated());
        assertEquals(8, limited.totalMatched());

        AuditPage rejected = log.query(Set.of(AuditRecord.Action.REJECTED), null, null, 50);
        assertEquals(1, rejected.returned());
        assertEquals("r1", rejected.records().get(0).changeId());

        AuditPage rolledBack = log.query(Set.of(AuditRecord.Action.ROLLED_BACK), null, null, 50);
        assertEquals("oncall", rolledBack.records().get(0).operator());

        AuditLogTest.log.info("[test] 输入: 8条记录 limit=3/按动作筛选 | 结果: 截断={} 拒绝={} 回滚={}",
                limited.truncated(), rejected.returned(), rolledBack.returned());
    }

    @Test
    void query_timeWindowFilter() throws Exception {
        AuditLog log = new AuditLog(100);
        AuditRecord old = log.append("old", "s", null, 0, 1, AuditRecord.Action.APPLIED, "old");
        Thread.sleep(15);
        long boundary = System.currentTimeMillis();
        Thread.sleep(15);
        AuditRecord fresh = log.append("new", "s", null, 1, 2, AuditRecord.Action.APPLIED, "new");

        // 以两条记录的真实时间戳之间取窗口，避免同毫秒抖动
        AuditPage since = log.query(null, boundary, null, 50);
        assertEquals(1, since.returned());
        assertEquals("new", since.records().get(0).changeId());

        AuditPage until = log.query(null, null, boundary, 50);
        assertEquals(1, until.returned());
        assertEquals("old", until.records().get(0).changeId());
        assertTrue(old.timestampMillis() < boundary && boundary < fresh.timestampMillis(),
                "两条记录必须落在边界两侧");
        AuditLogTest.log.info("[test] 输入: since/until={} old={} new={} | 结果: since命中=new until命中=old",
                boundary, old.timestampMillis(), fresh.timestampMillis());
    }
}
