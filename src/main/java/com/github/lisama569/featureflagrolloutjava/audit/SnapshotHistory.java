package com.github.lisama569.featureflagrolloutjava.audit;

import com.github.lisama569.featureflagrolloutjava.domain.MergedConfig;

import java.util.List;
import java.util.Optional;

/**
 * 生效快照历史：支持回滚到指定版本，并始终保留最近一次可用快照用于降级。
 */
public interface SnapshotHistory {
    void retain(MergedConfig config);

    Optional<MergedConfig> current();

    Optional<MergedConfig> version(long version);

    List<MergedConfig> versions();
}
