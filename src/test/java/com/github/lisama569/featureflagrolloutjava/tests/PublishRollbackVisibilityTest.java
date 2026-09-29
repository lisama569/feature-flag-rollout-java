package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.BatchPublishRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次发布与回滚并发时，读取方拿到的永远是“完整旧结果或完整新结果”：
 * 逐键拷贝整份快照后在锁外比对，杜绝读到半新半旧；最终状态也必须是某个完整状态。
 */
class PublishRollbackVisibilityTest {
    private static final Logger log = LoggerFactory.getLogger(PublishRollbackVisibilityTest.class);

    private static final List<String> KEYS = List.of(
            "feature.a.enabled", "feature.a.percentage",
            "feature.b.enabled", "feature.b.percentage",
            "feature.c.enabled", "feature.c.percentage");

    private ConfigService newService() {
        InMemoryConfigSource defaults = new InMemoryConfigSource("defaults", 0);
        defaults.setEntries(Map.of(
                "feature.a.enabled", "false", "feature.a.percentage", "0",
                "feature.b.enabled", "false", "feature.b.percentage", "0",
                "feature.c.enabled", "false", "feature.c.percentage", "0"));
        return new ConfigService(List.of(defaults, new InMemoryConfigSource("dynamic", 100)),
                new ConfigValidator(), new AuditLog());
    }

    private static boolean isOld(Map<String, String> e) {
        return "false".equals(e.get("feature.a.enabled")) && "0".equals(e.get("feature.a.percentage"))
                && "false".equals(e.get("feature.b.enabled")) && "0".equals(e.get("feature.b.percentage"))
                && "false".equals(e.get("feature.c.enabled")) && "0".equals(e.get("feature.c.percentage"));
    }

    private static boolean isNew(Map<String, String> e) {
        return "true".equals(e.get("feature.a.enabled")) && "55".equals(e.get("feature.a.percentage"))
                && "true".equals(e.get("feature.b.enabled")) && "55".equals(e.get("feature.b.percentage"))
                && "true".equals(e.get("feature.c.enabled")) && "55".equals(e.get("feature.c.percentage"));
    }

    @Test
    void readersDuringConcurrentBatchAndRollback_alwaysSeeWholeOldOrWholeNewSnapshot() throws Exception {
        ConfigService service = newService();
        long v0 = service.currentSnapshot().version();

        int readers = 8;
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(readers + 2);

        // 写线程 A：反复整批发布 a/b/c 一起 55
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 80; i++) {
                    long v = service.currentSnapshot().version();
                    try {
                        service.publishBatch(new BatchPublishRequest("up-" + i, "release", "alice", v, List.of(
                                new BatchPublishRequest.BatchItem("a", Map.of(
                                        "feature.a.enabled", "true", "feature.a.percentage", "55")),
                                new BatchPublishRequest.BatchItem("b", Map.of(
                                        "feature.b.enabled", "true", "feature.b.percentage", "55")),
                                new BatchPublishRequest.BatchItem("c", Map.of(
                                        "feature.c.enabled", "true", "feature.c.percentage", "55")))));
                    } catch (Exception ignore) {
                        // 与回滚并发导致的过期基准拒绝是预期的
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 写线程 B：反复整批回滚到初始版本（a/b/c 一起 0）
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 80; i++) {
                    try {
                        service.rollback(v0, "oncall");
                    } catch (Exception ignore) {
                        // v0 不会在默认保留窗口内被淘汰；其它异常不影响可见性验证
                    }
                    Thread.yield();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 读线程：每次先拿到不可变快照，再把整份键值在锁外拷贝出来核对
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 3000; i++) {
                        ConfigSnapshot snap = service.currentSnapshot();
                        Map<String, String> copy = new java.util.HashMap<>();
                        for (String k : KEYS) {
                            copy.put(k, snap.get(k));
                        }
                        boolean old = isOld(copy);
                        boolean now = isNew(copy);
                        if (!old && !now) {
                            violations.add("非完整旧/新状态 v" + snap.version() + ": " + copy);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发测试超时");

        // 最终状态同样必须是完整旧或完整新
        Map<String, String> finalEntries = new java.util.HashMap<>();
        for (String k : KEYS) {
            finalEntries.put(k, service.currentSnapshot().get(k));
        }
        boolean finalWhole = isOld(finalEntries) || isNew(finalEntries);

        log.info("[test] 输入: 80 次整批发布 vs 80 次整批回滚 + {} 读线程 x3000 | 结果: 非完整状态次数={} 最终完整={}",
                readers, violations.size(), finalWhole);
        assertEquals(0, violations.size(), "读取方看到半新半旧: " + violations.peek());
        assertTrue(finalWhole, "最终状态也必须是完整旧或完整新: " + finalEntries);
    }
}
