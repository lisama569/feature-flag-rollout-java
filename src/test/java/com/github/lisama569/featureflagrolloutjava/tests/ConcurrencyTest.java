package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.config.AuditLog;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.config.InMemoryConfigSource;
import com.github.lisama569.featureflagrolloutjava.config.RejectionReason;
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

/** 并发读取与配置切换的一致性：不得观察到半新半旧的中间状态。 */
class ConcurrencyTest {
    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @Test
    void concurrentReadsDuringSwitch_neverObserveMixedState() throws Exception {
        InMemoryConfigSource source = new InMemoryConfigSource("dynamic", 100);
        source.setEntries(Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "0"));
        // 本测试要逐条对照历史版本快照重算判定结果，显式给出足够大的历史保留上限，
        // 避免默认上限在 200 次切换后淘汰旧版本而干扰一致性校验
        ConfigService configService = new ConfigService(List.of(source), new ConfigValidator(),
                new AuditLog(), 10_000);
        FeatureFlagService flags = new FeatureFlagService(configService);

        int readerCount = 8;
        ConcurrentLinkedQueue<Decision> decisions = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);

        // 写线程：在 0% 与 100% 之间反复切换灰度比例
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 200; i++) {
                    long base = configService.currentSnapshot().version();
                    String pct = (i % 2 == 0) ? "100" : "0";
                    configService.submitChange(new ChangeRequest("toggle-" + i, "writer", base,
                            Map.of("feature.pay.percentage", pct)));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 读线程：切换期间持续判定
        for (int r = 0; r < readerCount; r++) {
            int readerId = r;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2000; i++) {
                        decisions.add(flags.evaluate("pay", "reader-" + readerId + "-subj-" + (i % 10)));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发测试超时");

        // 校验：每个判定结果都必须与其声称的版本所对应的快照完全一致（无新旧混用）
        int checked = 0;
        for (Decision d : decisions) {
            ConfigSnapshot snap = configService.history().get(d.configVersion());
            if (snap == null) {
                continue; // 历史超过保留上限被淘汰时跳过（本测试显式给了足够大的上限，不会走到）
            }
            boolean masterOn = Boolean.parseBoolean(snap.get("feature.pay.enabled"));
            int pct = Integer.parseInt(snap.get("feature.pay.percentage"));
            boolean expected = masterOn && Bucketing.bucket(d.flagKey(), d.subjectId()) < pct;
            assertEquals(expected, d.enabled(),
                    "判定结果与版本 " + d.configVersion() + " 的快照不一致，出现新旧混用: " + d);
            assertEquals(pct, d.percentage(), "判定依据的比例必须来自同一快照");
            checked++;
        }
        log.info("[test] 输入: {} 个读线程 x 2000 次判定 + 200 次切换 | 依据: 每次判定对照其版本快照重算 | 结果: {} 条判定全部一致",
                readerCount, checked);
        assertEquals(readerCount * 2000, checked);
    }

    @Test
    void concurrentDuplicateSubmit_exactlyOneApplies() throws Exception {
        InMemoryConfigSource source = new InMemoryConfigSource("dynamic", 100);
        ConfigService configService = new ConfigService(List.of(source), new ConfigValidator(), new AuditLog());
        long base = configService.currentSnapshot().version();

        int threads = 16;
        AtomicInteger applied = new AtomicInteger();
        AtomicInteger duplicateRejected = new AtomicInteger();
        AtomicInteger staleRejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    configService.submitChange(new ChangeRequest("same-id", "race", base,
                            Map.of("feature.pay.percentage", "42")));
                    applied.incrementAndGet();
                } catch (ChangeRejectedException e) {
                    if (e.reason() == RejectionReason.DUPLICATE_CHANGE_ID) {
                        duplicateRejected.incrementAndGet();
                    } else if (e.reason() == RejectionReason.STALE_BASE_VERSION) {
                        staleRejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("[test] 输入: {} 线程并发提交同一 changeId | 依据: changeId 幂等去重 | 结果: 应用={} 重复拒绝={} 过期拒绝={} 生效值={}",
                threads, applied.get(), duplicateRejected.get(), staleRejected.get(),
                configService.get("feature.pay.percentage"));
        assertEquals(1, applied.get(), "同一 changeId 只能被应用一次");
        assertEquals(threads - 1, duplicateRejected.get() + staleRejected.get());
        assertEquals("42", configService.get("feature.pay.percentage"));
    }

    @Test
    void concurrentRefreshAndRead_snapshotAlwaysConsistent() throws Exception {
        InMemoryConfigSource source = new InMemoryConfigSource("dynamic", 100);
        source.setEntries(Map.of(
                "feature.pay.enabled", "true",
                "feature.pay.percentage", "50"));
        ConfigService configService = new ConfigService(List.of(source), new ConfigValidator(), new AuditLog());

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger inconsistent = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            boolean reader = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 500; j++) {
                        if (reader) {
                            ConfigSnapshot snap = configService.currentSnapshot();
                            // 同一快照内的读取必须自洽：版本与条目来自同一次原子切换
                            String pct = snap.get("feature.pay.percentage");
                            String enabled = snap.get("feature.pay.enabled");
                            if (pct == null || enabled == null) {
                                inconsistent.incrementAndGet();
                            }
                        } else {
                            configService.refresh();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        log.info("[test] 输入: 并发 refresh 与快照读取 | 依据: 快照不可变且原子切换 | 结果: 不一致次数={}", inconsistent.get());
        assertEquals(0, inconsistent.get());
    }
}
