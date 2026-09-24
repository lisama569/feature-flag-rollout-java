package com.github.lisama569.featureflagrolloutjava.merge;

import com.github.lisama569.featureflagrolloutjava.domain.MergedConfig;
import com.github.lisama569.featureflagrolloutjava.domain.SourceSnapshot;
import com.github.lisama569.featureflagrolloutjava.domain.ValidationIssue;

import java.util.List;

/**
 * 按来源优先级分层合并：高优先级来源的合法条目覆盖低优先级；
 * 非法条目绝不静默覆盖已生效取值。
 */
public interface ConfigMerger {
    MergedConfig merge(long newVersion,
                       List<SourceSnapshot> priorityOrderedSnapshots,
                       List<ValidationIssue> rejected,
                       List<com.github.lisama569.featureflagrolloutjava.domain.DegradedSource> degraded);
}
