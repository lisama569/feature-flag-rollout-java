package com.github.lisama569.featureflagrolloutjava.config;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 内存配置来源，便于测试与本地验证；可模拟来源不可用。 */
public class InMemoryConfigSource implements ConfigSource {
    private final String name;
    private final int priority;
    private final AtomicReference<Map<String, String>> entries = new AtomicReference<>(Map.of());
    private final AtomicBoolean available = new AtomicBoolean(true);

    public InMemoryConfigSource(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    public void setEntries(Map<String, String> entries) {
        this.entries.set(Map.copyOf(entries));
    }

    public void setAvailable(boolean available) {
        this.available.set(available);
    }

    @Override
    public String name() { return name; }

    @Override
    public int priority() { return priority; }

    @Override
    public Map<String, String> load() throws ConfigSourceUnavailableException {
        if (!available.get()) {
            throw new ConfigSourceUnavailableException("配置来源不可用: " + name);
        }
        return entries.get();
    }
}
