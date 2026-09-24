package com.github.lisama569.featureflagrolloutjava.audit;

import com.github.lisama569.featureflagrolloutjava.domain.MergedConfig;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存快照历史：
 * <ul>
 *   <li>{@link #current()} 始终指向最近一次可用快照 —— 来源不可用时上层据此降级；</li>
 *   <li>每个发布过的版本都保留，支持按版本回滚；</li>
 *   <li>所有快照不可变，多线程读取无需加锁。</li>
 * </ul>
 */
@Component
public class InMemorySnapshotHistory implements SnapshotHistory {

    private final CopyOnWriteArrayList<MergedConfig> history = new CopyOnWriteArrayList<>();
    private volatile MergedConfig current;

    @Override
    public void retain(MergedConfig config) {
        history.add(config);
        current = config;
    }

    @Override
    public Optional<MergedConfig> current() {
        return Optional.ofNullable(current);
    }

    @Override
    public Optional<MergedConfig> version(long version) {
        return history.stream().filter(c -> c.version() == version).findFirst();
    }

    @Override
    public List<MergedConfig> versions() {
        return List.copyOf(history);
    }
}
