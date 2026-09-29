package com.github.lisama569.featureflagrolloutjava.config;

import java.util.List;

/**
 * 可回滚版本历史的保留状况（历史有明确上限，超出按 FIFO 淘汰最老版本，避免内存只增不减）。
 *
 * @param retentionLimit       最多保留的历史版本数（含当前版本）
 * @param retainedCount        当前保留中的版本数
 * @param retainedVersions     当前保留中的版本号（升序）
 * @param oldestRetainedVersion 当前保留中的最老版本号（无历史时为 null）
 * @param evictedVersionCount  因超过保留上限被淘汰的版本总数
 * @param recentlyEvictedVersions 最近被淘汰的版本号（升序，只保留少量用于提示，最多 {@link ConfigService#RECENT_EVICTED_TRACK} 个）
 */
public record HistoryRetentionStatus(int retentionLimit,
                                     int retainedCount,
                                     List<Long> retainedVersions,
                                     Long oldestRetainedVersion,
                                     int evictedVersionCount,
                                     List<Long> recentlyEvictedVersions) {
}
