package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.audit.InMemoryAuditLog;
import com.github.lisama569.featureflagrolloutjava.audit.InMemorySnapshotHistory;
import com.github.lisama569.featureflagrolloutjava.audit.SnapshotHistory;
import com.github.lisama569.featureflagrolloutjava.domain.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry;
import com.github.lisama569.featureflagrolloutjava.domain.EvaluationResult;
import com.github.lisama569.featureflagrolloutjava.domain.FlagEntry;
import com.github.lisama569.featureflagrolloutjava.domain.MergedConfig;
import com.github.lisama569.featureflagrolloutjava.domain.PropertyEntry;
import com.github.lisama569.featureflagrolloutjava.domain.RejectReason;
import com.github.lisama569.featureflagrolloutjava.merge.PriorityConfigMerger;
import com.github.lisama569.featureflagrolloutjava.rollout.Bucketer;
import com.github.lisama569.featureflagrolloutjava.rollout.FnvBucketer;
import com.github.lisama569.featureflagrolloutjava.service.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.service.FeatureFlagService;
import com.github.lisama569.featureflagrolloutjava.source.MapConfigSource;
import com.github.lisama569.featureflagrolloutjava.validation.StrictConfigValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发测试：大量读取线程与配置切换并发执行。
 * <p>
 * 核心不变量：每次 evaluate 的结果必须能与"结果中记录的那个配置版本"的不可变快照
 * 完全对上（开关注解、放量比例、桶值、取值全部自洽）——这就证明单次判定内不存在
 * 新旧分支混用或半新半旧；至于不同调用观察到不同版本，本身就是热更新的预期行为。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    private static final String FLAG = "dark-mode";

    @Test
    void concurrentReadsNeverSeeIntermediateState() throws Exception {
        MapConfigSource remote = new MapConfigSource("remote", 100, List.of(entries(0)));
        SnapshotHistory history = new InMemorySnapshotHistory();
        Bucketer bucketer = new FnvBucketer();
        FeatureFlagService service = new FeatureFlagService(
                List.of(remote), new StrictConfigValidator(), new PriorityConfigMerger(),
                bucketer, new InMemoryAuditLog(), history);
        service.reload();

        int readers = 12;
        int iterations = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
        CyclicBarrier barrier = new CyclicBarrier(readers + 1);
        AtomicInteger violations = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ConcurrentLinkedQueue<Long> observedVersions = new ConcurrentLinkedQueue<>();

        for (int r = 0; r < readers; r++) {
            final String subject = "reader-" + r;
            pool.submit(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < iterations; i++) {
                        EvaluationResult eval = service.evaluate(FLAG, subject);
                        observedVersions.add(eval.configVersion());
                        // 取回该次判定所声称版本的不可变快照，逐项对账
                        MergedConfig snap = history.version(eval.configVersion()).orElse(null);
                        if (snap == null || !isSelfConsistent(eval, snap, bucketer, subject)) {
                            violations.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }

        pool.submit(() -> {
            try {
                barrier.await();
                for (int round = 1; round <= 20; round++) {
                    long expected = remote.load().sourceVersion();
                    service.submit(new ChangeRequest(UUID.randomUUID().toString(), "remote",
                            expected, List.of(entries(round))));
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        if (failure.get() != null) {
            throw new AssertionError("并发执行出现异常", failure.get());
        }
        long distinctVersions = observedVersions.stream().distinct().count();
        log.info("输入: {} 读取线程 x {} 次, 并发 20 轮切换 | 判定依据: 单次判定结果与其声明版本快照逐项对账 | 最终生效: 总判定={}, 违规={}, 观察到版本数={}",
                readers, iterations, readers * iterations, violations.get(), distinctVersions);
        assertEquals(0, violations.get(), "存在与所属快照不自洽的判定（新旧混用/半新半旧）");
        assertTrue(distinctVersions >= 2, "读取方应观察到多个配置版本");
    }

    /**
     * 结果必须与其声明版本的不可变快照完全自洽：
     * 比例、桶值、命中、取值均由同一个快照定义推导得出。
     */
    private boolean isSelfConsistent(EvaluationResult eval, MergedConfig snap,
                                     Bucketer bucketer, String subject) {
        FlagEntry flag = snap.flags().get(FLAG);
        if (flag == null) {
            return false;
        }
        int expectedBucket = bucketer.bucket(flag.key(), subject, flag.variantKey());
        if (eval.bucket() != expectedBucket) {
            return false;
        }
        if (eval.percentage() != flag.rolloutPercentage()) {
            return false;
        }
        boolean expectedValue;
        if (!flag.enabled()) {
            expectedValue = flag.defaultValue();
        } else {
            expectedValue = (expectedBucket < flag.rolloutPercentage()) != flag.defaultValue();
        }
        return eval.value() == expectedValue;
    }

    @Test
    void concurrentSubmitsOnlyOneWinsOthersRejectedAsStale() throws Exception {
        MapConfigSource remote = new MapConfigSource("remote", 100,
                List.<ConfigEntry>of(new PropertyEntry("endpoint", "http://v0")));
        FeatureFlagService service = new FeatureFlagService(
                List.of(remote), new StrictConfigValidator(), new PriorityConfigMerger(),
                new FnvBucketer(), new InMemoryAuditLog(), new InMemorySnapshotHistory());
        service.reload();

        int n = 16;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    barrier.await();
                    // 所有提交者都基于 v0，内容各不相同，避免 NO_OP
                    var req = new ChangeRequest("race-" + idx, "remote", 0,
                            List.<ConfigEntry>of(new PropertyEntry("endpoint", "http://writer-" + idx)));
                    service.submit(req);
                    accepted.incrementAndGet();
                } catch (ChangeRejectedException e) {
                    if (e.reason() == RejectReason.STALE_VERSION) {
                        stale.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        log.warn("非预期拒绝原因: {}", e.reason());
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("输入: {} 个提交者同时基于 v0 提交不同内容 | 判定依据: 写操作串行化+乐观版本 | 最终生效: 接受={}, 过期拒绝={}, 其他={}, 生效值={}",
                n, accepted.get(), stale.get(), other.get(), service.getProperty("endpoint").orElseThrow());
        assertEquals(1, accepted.get(), "只能有一个提交成功");
        assertEquals(n - 1, stale.get(), "其余必须以 STALE_VERSION 被明确拒绝");
        assertEquals(0, other.get());
    }

    private static ConfigEntry entries(int round) {
        // 每轮改放量比例，制造可观察的版本差异
        int percentage = round == 0 ? 10 : (10 + round * 4) % 101;
        return new FlagEntry(FLAG, true, percentage, false, "ui-exp");
    }
}
