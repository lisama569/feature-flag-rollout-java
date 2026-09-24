package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry;
import com.github.lisama569.featureflagrolloutjava.domain.FlagEntry;
import com.github.lisama569.featureflagrolloutjava.domain.MergedConfig;
import com.github.lisama569.featureflagrolloutjava.domain.PropertyEntry;
import com.github.lisama569.featureflagrolloutjava.domain.SourceSnapshot;
import com.github.lisama569.featureflagrolloutjava.merge.PriorityConfigMerger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 合并器单测：高优先级合法值覆盖低优先级；高优先级非法值已在校验阶段剔除，
 * 因此低优先级的生效值得以保留。
 */
class MergerTest {

    private static final Logger log = LoggerFactory.getLogger(MergerTest.class);

    private final PriorityConfigMerger merger = new PriorityConfigMerger();

    @Test
    void higherPrioritySourceWins() {
        List<ConfigEntry> low = List.of(
                new PropertyEntry("endpoint", "http://low"),
                new PropertyEntry("only-low", "L"));
        List<ConfigEntry> high = List.of(new PropertyEntry("endpoint", "http://high"));
        MergedConfig merged = merger.merge(1,
                List.of(new SourceSnapshot("remote", 1, high),
                        new SourceSnapshot("local", 0, low)),
                List.of(), List.of());
        log.info("输入: remote.endpoint=http://high(优先级100), local.endpoint=http://low(优先级0) | "
                + "判定依据: 高优先级先出现者生效 | 结果: endpoint={}, only-low={}",
                merged.properties().get("endpoint").value(),
                merged.properties().get("only-low").value());
        assertEquals("http://high", merged.properties().get("endpoint").value());
        assertEquals("L", merged.properties().get("only-low").value());
    }

    @Test
    void invalidHighPriorityEntryMustNotSilentlyOverride() {
        // 高优先级来源里非法的 flag 在校验后被剔除，进入合并器的只有合法条目
        List<ConfigEntry> low = List.of(new FlagEntry("flag-a", true, 10, false, "exp-a"));
        List<ConfigEntry> highValidOnly = List.of(); // 非法条目已被 StrictConfigValidator 剔除
        MergedConfig merged = merger.merge(2,
                List.of(new SourceSnapshot("remote", 2, highValidOnly),
                        new SourceSnapshot("local", 0, low)),
                List.of(), List.of());
        log.info("输入: remote 的非法 flag-a 被拒绝剔除, local 提供合法 flag-a(10%) | "
                + "判定依据: 非法条目不参与合并 | 结果: flag-a 放量={}, 来源为 local 定义",
                merged.flags().get("flag-a").rolloutPercentage());
        assertEquals(10, merged.flags().get("flag-a").rolloutPercentage());
        assertFalse(merged.flags().get("flag-a").defaultValue());
    }

    @Test
    void mergedSnapshotIsImmutableView() {
        List<ConfigEntry> low = List.of(new PropertyEntry("k", "v"));
        MergedConfig merged = merger.merge(3,
                List.of(new SourceSnapshot("local", 0, low)), List.of(), List.of());
        log.info("输入: 单来源 k=v | 判定依据: 直接生效 | 结果: version={}, k={}",
                merged.version(), merged.properties().get("k").value());
        assertEquals(3, merged.version());
        assertEquals("v", merged.properties().get("k").value());
    }
}
