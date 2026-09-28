package com.github.lisama569.featureflagrolloutjava.config;

import com.github.lisama569.featureflagrolloutjava.ops.AuditPage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 线程安全、有容量上限的审计日志。
 *
 * <p>只保留最近 {@code capacity} 条记录（最老的被淘汰并计数），因此不会随历史无限增长吃内存。
 * seq 全局递增、永不复用，即使记录被淘汰也能从 {@link #evictedCount()} 看出曾经发生过多少写入。
 * 查询支持按结果（动作）与时间区间筛选，并显式给出截断提示。</p>
 */
public class AuditLog {
    /** 单次查询默认/最大返回条数，避免一次返回过多。 */
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    /** 默认保留容量。 */
    public static final int DEFAULT_CAPACITY = 500;

    private final int capacity;
    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicInteger evicted = new AtomicInteger(0);
    private final Deque<AuditRecord> records = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    public AuditLog() {
        this(DEFAULT_CAPACITY);
    }

    public AuditLog(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("审计容量必须为正: " + capacity);
        }
        this.capacity = capacity;
    }

    public AuditRecord append(String changeId, String source, long fromVersion, long toVersion,
                              AuditRecord.Action action, String detail) {
        return append(changeId, source, source, fromVersion, toVersion, action, detail, List.of());
    }

    public AuditRecord append(String changeId, String source, String operator,
                              long fromVersion, long toVersion,
                              AuditRecord.Action action, String detail, List<String> affectedKeys) {
        AuditRecord record = new AuditRecord(seq.incrementAndGet(), changeId, source, operator,
                fromVersion, toVersion, action, detail,
                List.copyOf(affectedKeys), System.currentTimeMillis());
        lock.lock();
        try {
            records.addLast(record);
            while (records.size() > capacity) {
                records.pollFirst();
                evicted.incrementAndGet();
            }
        } finally {
            lock.unlock();
        }
        return record;
    }

    /** 当前保留的全部记录（按时间正序拷贝）。 */
    public List<AuditRecord> records() {
        lock.lock();
        try {
            return List.copyOf(records);
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    public int evictedCount() {
        return evicted.get();
    }

    /**
     * 按结果与时间区间筛选审计记录，默认按时间倒序（最新在前），最多返回 limit 条。
     * 任何参数为 null 表示该条件不限；limit 越界会被夹到 [1, MAX_LIMIT]。
     */
    public AuditPage query(AuditRecord.Action action, Long fromMillis, Long toMillis, Integer limit) {
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        List<AuditRecord> matched = new ArrayList<>();
        lock.lock();
        try {
            for (AuditRecord r : records) { // 正序遍历
                if (action != null && r.action() != action) {
                    continue;
                }
                if (fromMillis != null && r.timestampMillis() < fromMillis) {
                    continue;
                }
                if (toMillis != null && r.timestampMillis() > toMillis) {
                    continue;
                }
                matched.add(r);
            }
        } finally {
            lock.unlock();
        }
        int total = matched.size();
        // 倒序（最新在前）并截断
        List<AuditRecord> page = new ArrayList<>(Math.min(effectiveLimit, total));
        for (int i = total - 1; i >= 0 && page.size() < effectiveLimit; i--) {
            page.add(matched.get(i));
        }
        String actionFilter = action == null ? null : action.name();
        return new AuditPage(List.copyOf(page), effectiveLimit, total, total > effectiveLimit,
                capacity, evicted.get(), actionFilter, fromMillis, toMillis);
    }
}
