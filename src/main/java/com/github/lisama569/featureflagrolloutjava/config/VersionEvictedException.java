package com.github.lisama569.featureflagrolloutjava.config;

/**
 * 回滚目标版本曾经存在、但已因版本历史保留上限被淘汰时抛出。
 * 与“版本从未存在”（{@link IllegalArgumentException}）明确区分：
 * 调用方能清楚知道该版本已不可用、以及当前可回滚的最早版本，
 * 而不是静默恢复成别的内容或拿到一个看不懂的错误。
 */
public class VersionEvictedException extends RuntimeException {
    private final long requestedVersion;
    private final long earliestAvailableVersion;
    private final int historyRetention;

    public VersionEvictedException(long requestedVersion, long earliestAvailableVersion,
                                   int historyRetention) {
        super("回滚目标版本已被淘汰不可用: version=" + requestedVersion
                + "（历史只保留最近 " + historyRetention + " 个版本，当前可回滚的最早版本为 "
                + earliestAvailableVersion + "）");
        this.requestedVersion = requestedVersion;
        this.earliestAvailableVersion = earliestAvailableVersion;
        this.historyRetention = historyRetention;
    }

    public long requestedVersion() { return requestedVersion; }

    public long earliestAvailableVersion() { return earliestAvailableVersion; }

    public int historyRetention() { return historyRetention; }
}
