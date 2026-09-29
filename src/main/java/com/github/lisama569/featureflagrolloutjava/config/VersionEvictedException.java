package com.github.lisama569.featureflagrolloutjava.config;

/**
 * 回滚目标版本曾经存在，但已因超过历史保留上限被淘汰，无法再回滚到它。
 * 继承 {@link IllegalArgumentException} 以保持对旧调用方的兼容；
 * 调用方应据 {@link #oldestRetainedVersion()} 提示用户选择仍可回滚的版本，
 * 而不是静默恢复成别的内容。
 */
public class VersionEvictedException extends IllegalArgumentException {
    private final long version;
    private final long oldestRetainedVersion;
    private final int retentionLimit;

    public VersionEvictedException(long version, long oldestRetainedVersion, int retentionLimit) {
        super("回滚目标版本 v" + version + " 已超过历史保留上限（最多保留 " + retentionLimit
                + " 个版本）被淘汰，无法回滚；当前可回滚的最早版本为 v" + oldestRetainedVersion
                + "，请改回滚到该版本或更新的版本。");
        this.version = version;
        this.oldestRetainedVersion = oldestRetainedVersion;
        this.retentionLimit = retentionLimit;
    }

    public long version() {
        return version;
    }

    public long oldestRetainedVersion() {
        return oldestRetainedVersion;
    }

    public int retentionLimit() {
        return retentionLimit;
    }
}
