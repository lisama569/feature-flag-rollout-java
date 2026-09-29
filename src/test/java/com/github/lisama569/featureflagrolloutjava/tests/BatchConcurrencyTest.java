package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.BatchPublishRequest;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.RejectionReason;
import com.github.lisama569.featureflagrolloutjava.rollout.FeatureFlagService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发场景：试算 / 批次发布 / 回滚 / 灰度判定混在一起时，
 * 批次仍然整批可见、同 batchId 只生效一次，读取方不观察到半新半旧。
 */
class BatchConcurrencyTest {
    private static final Logger log = LoggerFactory.getLogger(BatchConcurrencyTest.class);

    private ConfigService newService() {
        InMemoryConfigSource defaults = new InMemoryConfigSource("defaults", 0);
        defaults.setEntries(Map.of(
                "feature.a.enabled", "false", "feature.a.percentage", "0",
                "feature.b.enabled", "false", "feature.b.percentage", "0"));
        InMemoryConfigSource dynamic = new InMemoryConfigSource("dynamic", 100);
        // 并发用例会制造数百个版本并反复回滚到初始版本，显式给出足够大的历史保留上限，
        // 避免默认上限淘汰初始版本而干扰并发语义校验（淘汰行为由专门的历史保留用例覆盖）
        return new ConfigService(List.of(defaults, dynamic), new ConfigValidator(),
                new AuditLog(), 10_000);
    }

    private BatchPublishRequest batch(String id, long base) {
        return new BatchPublishRequest(id, "release", "racer", base, List.of(
                new BatchPublishRequest.BatchItem("a",
                        Map.of("feature.a.enabled", "true", "feature.a.percentage", "55")),
                new BatchPublishRequest.BatchItem("b",
                        Map.of("feature.b.enabled", "true", "feature.b.percentage", "55"))));
    }

    @Test
    void concurrentSameBatchId_appliesExactlyOnce() throws Exception {
        ConfigService service = newService();
        long base = service.currentSnapshot().version();
        int threads = 16;
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.publishBatch(batch("same-batch", base));
                    applied.incrementAndGet();
                } catch (ChangeRejectedException e) {
                    assertTrue(e.reason() == RejectionReason.DUPLICATE_CHANGE_ID
                            || e.reason() == RejectionReason.STALE_BASE_VERSION);
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("[test] 输入: {} 线程并发同一 batchId | 依据: 幂等+写锁串行 | 结果: 应用={} 拒绝={} 版本={}",
                threads, applied.get(), rejected.get(), service.currentSnapshot().version());
        assertEquals(1, applied.get(), "同一批次只能整批生效一次");
        assertEquals(threads - 1, rejected.get());
        assertEquals(base + 1, service.currentSnapshot().version(), "只前进一个版本");
        assertEquals("55", service.get("feature.a.percentage"));
        assertEquals("55", service.get("feature.b.percentage"));
    }

    @Test
    void readersDuringConcurrentBatchAndRollback_neverSeeHalfAppliedBatch() throws Exception {
        ConfigService service = newService();
        FeatureFlagService flags = new FeatureFlagService(service);

        int readerCount = 8;
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 3);

        // 写线程 A：反复整批发布（a、b 同时 55）
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 100; i++) {
                    long v = service.currentSnapshot().version();
                    service.publishBatch(batch("batch-up-" + i, v));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // 写线程 B：反复回滚到初始版本（a、b 同时 0）
        pool.submit(() -> {
            try {
                start.await();
                long v0 = 1L; // 构造器 refresh 后初始版本
                for (int i = 0; i < 100; i++) {
                    service.rollback(v0, "rollbacker");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // 试算线程：持续试算，绝不改变状态
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    long v = service.currentSnapshot().version();
                    service.previewChange(new com.github.lisama569.featureflagrolloutjava
                            .config.ChangeRequest("pv-" + i, "previewer", v,
                            Map.of("feature.a.percentage", "42")));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 读线程：任何时刻看到的快照里 a 与 b 的配置必须“整批一致”——
        // 要么都是旧值(enabled=false,0)，要么都是新值(enabled=true,55)
        for (int r = 0; r < readerCount; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2000; i++) {
                        ConfigSnapshot snap = service.currentSnapshot();
                        boolean aOn = "true".equals(snap.get("feature.a.enabled"));
                        boolean bOn = "true".equals(snap.get("feature.b.enabled"));
                        String aPct = snap.get("feature.a.percentage");
                        String bPct = snap.get("feature.b.percentage");
                        boolean oldState = !aOn && !bOn && "0".equals(aPct) && "0".equals(bPct);
                        boolean newState = aOn && bOn && "55".equals(aPct) && "55".equals(bPct);
                        if (!oldState && !newState) {
                            violations.add("观察到半新半旧: v" + snap.version()
                                    + " a(" + aOn + "," + aPct + ") b(" + bOn + "," + bPct + ")");
                        }
                        // 灰度判定也必须与该快照自洽
                        flags.evaluate("a", "subj-x");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发测试超时");

        // 试算使用的 pv-* 标识绝不应该出现在真实 APPLIED 审计里（未占用幂等/未真正发布）
        boolean previewLeaked = service.auditLog().records().stream()
                .anyMatch(r -> r.changeId() != null && r.changeId().startsWith("pv-")
                        && r.action() == com.github.lisama569.featureflagrolloutjava.config.AuditRecord.Action.APPLIED);
        if (previewLeaked) {
            violations.add("试算标识被真实发布，污染了状态");
        }

        log.info("[test] 输入: 批次发布+回滚+试算+{} 读判定线程 | 依据: 快照原子切换 | 结果: 半新半旧/试算污染次数={}",
                readerCount, violations.size());
        assertEquals(0, violations.size(), "不得观察到半新半旧或试算污染: " + violations.peek());
    }
}
