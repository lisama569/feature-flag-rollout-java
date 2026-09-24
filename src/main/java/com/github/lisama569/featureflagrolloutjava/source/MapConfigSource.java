package com.github.lisama569.featureflagrolloutjava.source;

import com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry;
import com.github.lisama569.featureflagrolloutjava.domain.SourceSnapshot;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存型配置来源。
 * <p>
 * 内部快照用 volatile 引用持有，{@link #replace(SourceSnapshot)} 以新条目构造全新快照、
 * 版本号单调递增后一次性发布，调用方拿到的永远是完整快照。
 * {@link #setAvailable(boolean)} 用于在测试/演练中模拟来源不可用，触发上层降级。
 */
public class MapConfigSource implements ConfigSource {

    private final String sourceName;
    private final int sourcePriority;
    private final AtomicLong versionSeq = new AtomicLong(0);
    private volatile SourceSnapshot current;
    private volatile boolean available = true;

    public MapConfigSource(String name, int priority, List<ConfigEntry> initialEntries) {
        this.sourceName = name;
        this.sourcePriority = priority;
        this.current = new SourceSnapshot(name, 0, List.copyOf(initialEntries));
    }

    @Override
    public String name() {
        return sourceName;
    }

    @Override
    public int priority() {
        return sourcePriority;
    }

    @Override
    public SourceSnapshot load() {
        if (!available) {
            throw new SourceUnavailableException(
                    "config source '" + sourceName + "' is unavailable", null);
        }
        return current;
    }

    /**
     * 用新条目集完整替换来源内容。忽略传入快照中的来源名/版本，
     * 统一由来源自己分配名称与单调递增版本，防止伪造版本。
     */
    @Override
    public synchronized long replace(SourceSnapshot snapshot) {
        long newVersion = versionSeq.incrementAndGet();
        this.current = new SourceSnapshot(sourceName, newVersion, List.copyOf(snapshot.entries()));
        return newVersion;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }
}
