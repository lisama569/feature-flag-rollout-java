package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.BatchChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.RejectionReason;
import com.github.lisama569.featureflagrolloutjava.release.ReleaseService;
import com.github.lisama569.featureflagrolloutjava.rollout.Bucketing;
import com.github.lisama569.featureflagrolloutjava.rollout.Decision;
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

/** 批次原子性在并发（试算/发布/回滚/判定混合）下不得出现半新半旧。 */
class BatchConcurrencyTest {
    private static final Logger log = LoggerFactory.getLogger(BatchConcurrencyTest.class);

    private ConfigService newService() {
        InMemoryConfigSource source = new InMemoryConfigSource("dynamic", 100);
        source.setEntries(Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "0",
                "feature.cart.enabled", "true",
                "feature.cart.percentage", "0"));
        return new ConfigService(List.of(source), new ConfigValidator(), new AuditLog());
    }

    /** 每个批次都成对修改 pay 与 cart，若观察到两者不成组，即为半新半旧。 */
    private boolean snapshotIsCoherent(ConfigSnapshot s) {
        String pay = s.get("feature.pay.percentage");
        String cart = s.get("feature.cart.percentage");
        if (pay == null || cart == null) {
            return false;
        }
        // 成组约定：批次 i 同时把两者设为同一个值（10*i），初始都是 0
        return pay.equals(cart);
    }

    @Test
    void concurrentPublishRollbackPreflightAndEvaluate_neverSeeHalfBatch() throws Exception {
        ConfigService svc = newService();
        ReleaseService release = new ReleaseService(svc);
        FeatureFlagService flags = new FeatureFlagService(svc);

        int evaluators = 6;
        int preflighters = 2;
        ConcurrentLinkedQueue<Decision> decisions = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> incoherent = new ConcurrentLinkedQueue<>();
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(evaluators + preflighters + 3);

        // 发布线程：整批同时改 pay 与 cart
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 1; i <= 100; i++) {
                    long base = svc.currentSnapshot().version();
                    String v = String.valueOf(10 * (i % 10));
                    try {
                        release.publishBatch(new BatchChangeRequest("batch-" + i, "writer", "w", base,
                                Map.of("feature.pay.percentage", v,
                                        "feature.cart.percentage", v)));
                        applied.incrementAndGet();
                    } catch (ChangeRejectedException e) {
                        rejected.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 回滚线程：反复回滚到当前的前一版本（历史中一定存在）
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 50; i++) {
                    long cur = svc.currentSnapshot().version();
                    if (cur >= 1) {
                        try {
                            svc.rollback(cur - 1, "oncall");
                        } catch (IllegalArgumentException ignore) {
                            // 目标版本恰被历史上限淘汰时跳过（本测试远达不到上限）
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 试算线程：并发只读试算，绝不改变状态
        for (int p = 0; p < preflighters; p++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        long base = svc.currentSnapshot().version();
                        release.preflight(new BatchChangeRequest("pf-readonly-" + Thread.currentThread().getId()
                                + "-" + i, "pf", "pf-op", base,
                                Map.of("feature.pay.percentage", "33",
                                        "feature.cart.percentage", "33")));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 判定线程：持续对 pay 判定，并校验其依据版本的快照是成组一致的
        for (int r = 0; r < evaluators; r++) {
            int rid = r;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 1000; i++) {
                        Decision d = flags.evaluate("pay", "subj-" + rid + "-" + (i % 20));
                        decisions.add(d);
                        ConfigSnapshot s = svc.history().get(d.configVersion());
                        if (s != null && !snapshotIsCoherent(s)) {
                            incoherent.add("版本 " + d.configVersion() + " 出现半新半旧: " + s.entries());
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

        // 当前快照也必须是成组的
        assertTrue(snapshotIsCoherent(svc.currentSnapshot()),
                "最终快照半新半旧: " + svc.currentSnapshot().entries());
        log.info("[test] 输入: 发布+回滚+{}试算+{}判定 混合并发 | 依据: 批次成对设置 pay/cart | "
                        + "结果: 生效={} 拒绝={} 判定={} 半新半旧={}",
                preflighters, evaluators, applied.get(), rejected.get(), decisions.size(), incoherent.size());
        assertTrue(incoherent.isEmpty(), "检测到半新半旧: " + incoherent.stream().findFirst().orElse(""));
        assertTrue(applied.get() >= 1, "至少应有批次成功生效");
        assertEquals(0, incoherent.size());
    }

    @Test
    void concurrentSameBatchId_wholeBatchAppliesAtMostOnce() throws Exception {
        ConfigService svc = newService();
        ReleaseService release = new ReleaseService(svc);
        long base = svc.currentSnapshot().version();

        int threads = 20;
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger rejectedDupOrStale = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    release.publishBatch(new BatchChangeRequest("same-batch", "race", "op", base,
                            Map.of("feature.pay.percentage", "42",
                                    "feature.cart.percentage", "42")));
                    applied.incrementAndGet();
                } catch (ChangeRejectedException e) {
                    if (e.reason() == RejectionReason.DUPLICATE_CHANGE_ID
                            || e.reason() == RejectionReason.STALE_BASE_VERSION) {
                        rejectedDupOrStale.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("[test] 输入: {} 线程并发提交同一批次 | 依据: changeId 整批幂等 | 结果: 生效={} 拒绝={}",
                threads, applied.get(), rejectedDupOrStale.get());
        assertEquals(1, applied.get(), "同一批次只能整批生效一次");
        assertEquals(threads - 1, rejectedDupOrStale.get(),
                "其余并发提交必须全部被重复/过期拒绝，不得部分生效");
        assertEquals("42", svc.get("feature.pay.percentage"));
        assertEquals("42", svc.get("feature.cart.percentage"));
    }

    @Test
    void decisionAlwaysMatchesItsClaimedVersionUnderBatchTraffic() throws Exception {
        ConfigService svc = newService();
        ReleaseService release = new ReleaseService(svc);
        FeatureFlagService flags = new FeatureFlagService(svc);

        int readers = 6;
        ConcurrentLinkedQueue<Decision> decisions = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 150; i++) {
                    long base = svc.currentSnapshot().version();
                    String pct = (i % 2 == 0) ? "100" : "0";
                    try {
                        release.publishBatch(new BatchChangeRequest("traffic-" + i, "w", "w", base,
                                Map.of("feature.pay.percentage", pct,
                                        "feature.cart.percentage", pct)));
                    } catch (ChangeRejectedException ignore) { }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        for (int r = 0; r < readers; r++) {
            int rid = r;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 1500; i++) {
                        decisions.add(flags.evaluate("pay", "u-" + rid + "-" + (i % 10)));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        int checked = 0;
        for (Decision d : decisions) {
            ConfigSnapshot s = svc.history().get(d.configVersion());
            if (s == null) {
                continue;
            }
            boolean masterOn = Boolean.parseBoolean(s.get("feature.pay.enabled"));
            int pct = Integer.parseInt(s.get("feature.pay.percentage"));
            boolean expected = masterOn && Bucketing.bucket(d.flagKey(), d.subjectId()) < pct;
            assertEquals(expected, d.enabled(), "判定与声明版本快照不一致，出现新旧混用");
            checked++;
        }
        log.info("[test] 输入: 批次流量 + {} 判定线程 | 结果: {} 条判定均与其版本快照一致", readers, checked);
        assertEquals(decisions.size(), checked);
    }
}
