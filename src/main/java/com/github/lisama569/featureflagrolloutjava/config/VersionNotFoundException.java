package com.github.lisama569.featureflagrolloutjava.config;

/**
 * 回滚目标版本从未存在过（版本号超出已产生的范围）。
 * 继承 {@link IllegalArgumentException} 以保持对旧调用方的兼容。
 */
public class VersionNotFoundException extends IllegalArgumentException {
    private final long version;
    private final long currentVersion;

    public VersionNotFoundException(long version, long currentVersion) {
        super("回滚目标版本 v" + version + " 从未存在过（当前版本为 v" + currentVersion
                + "，历史上从未产生过该版本号）");
        this.version = version;
        this.currentVersion = currentVersion;
    }

    public long version() {
        return version;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
