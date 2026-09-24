package com.github.lisama569.featureflagrolloutjava.merge;

import com.github.lisama569.featureflagrolloutjava.domain.DegradedSource;
import com.github.lisama569.featureflagrolloutjava.domain.FlagEntry;
import com.github.lisama569.featureflagrolloutjava.domain.MergedConfig;
import com.github.lisama569.featureflagrolloutjava.domain.PropertyEntry;
import com.github.lisama569.featureflagrolloutjava.domain.SourceSnapshot;
import com.github.lisama569.featureflagrolloutjava.domain.ValidationIssue;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 优先级分层合并。
 * <p>
 * 输入快照已按优先级从高到低排列；对每个键，最先出现（即最高优先级来源提供）的
 * <b>合法</b>条目生效，低优先级的同键条目被忽略。非法条目在校验阶段已被剔除，
 * 因此高优先级来源的非法值永远不会静默覆盖低优先级的已生效值。
 * <p>
 * 输出的 {@link MergedConfig} 完全不可变，是读取方可见性的最小单位。
 */
@Component
public class PriorityConfigMerger implements ConfigMerger {

    @Override
    public MergedConfig merge(long newVersion,
                              List<SourceSnapshot> priorityOrderedSnapshots,
                              List<ValidationIssue> rejected,
                              List<DegradedSource> degraded) {
        Map<String, FlagEntry> flags = new LinkedHashMap<>();
        Map<String, PropertyEntry> properties = new LinkedHashMap<>();

        for (SourceSnapshot snapshot : priorityOrderedSnapshots) {
            for (var entry : snapshot.entries()) {
                if (entry instanceof FlagEntry flag) {
                    flags.putIfAbsent(flag.key(), flag);
                } else if (entry instanceof PropertyEntry prop) {
                    properties.putIfAbsent(prop.key(), prop);
                }
            }
        }

        return new MergedConfig(
                newVersion,
                Map.copyOf(flags),
                Map.copyOf(properties),
                List.copyOf(rejected),
                List.copyOf(degraded));
    }
}
