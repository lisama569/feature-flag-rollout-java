package com.github.lisama569.featureflagrolloutjava.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 线程安全、有容量上限的审计日志。
 *
 * <p>内部使用定长环形缓冲：历史无限增长时只会丢弃最旧的记录并计数，
 * 不会无界吃内存。查询始终返回最新的匹配记录，并通过 {@link AuditPage}
 * 明确告知是否发生截断、丢弃了多少条。</p>
 */
public class AuditLog {
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    /** 默认保留最近 1000 条审计记录。 */
    public static final int DEFAULT_CAPACITY = 1000;

    private final int capacity;
    private final AuditRecord[] ring;
    private final AtomicLong seq = new AtomicLong(0);
    /** 已写入的总条数（含已被环形缓冲挤出的）。 */
    private final AtomicLong totalAdded = new AtomicLong(0);
    private final ReentrantLock lock = new ReentrantLock();
    /** 环形缓冲中元素个数（0..capacity）与下一个写入位置。 */
    private int size = 0;
    private int head = 0;

    public AuditLog() {
        this(DEFAULT_CAPACITY);
    }

    public AuditLog(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("审计容量必须为正数: " + capacity);
        }
        this.capacity = capacity;
        this.ring = new AuditRecord[capacity];
    }

    /** 兼容旧调用方：追加一条不含操作者的记录。 */
    public AuditRecord append(String changeId, String source, long fromVersion, long toVersion,
                              AuditRecord.Action action, String detail) {
        return append(changeId, source, null, fromVersion, toVersion, action, detail);
    }

    /** 追加一条审计记录；缓冲满时丢弃最旧记录并计数。 */
    public AuditRecord append(String changeId, String source, String operator, long fromVersion,
                              long toVersion, AuditRecord.Action action, String detail) {
        AuditRecord record = new AuditRecord(seq.incrementAndGet(), changeId, source,
                fromVersion, toVersion, action, detail, System.currentTimeMillis(), operator);
        lock.lock();
        try {
            ring[head] = record;
            head = (head + 1) % capacity;
            if (size < capacity) {
                size++;
            }
            long added = totalAdded.incrementAndGet();
            if (added > capacity && added % 200 == 0) {
                log.debug("[audit] 环形缓冲已写满，最早记录被持续挤出，累计写入={}", added);
            }
            return record;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 兼容旧调用方：返回缓冲内全部记录（按时间正序，最旧在前）的不可变副本。
     * 注意：可能不含已被容量上限挤出的更老记录，新代码建议用 {@link #query}。
     */
    public List<AuditRecord> records() {
        lock.lock();
        try {
            List<AuditRecord> all = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                all.add(ring[(head - size + i + capacity) % capacity]);
            }
            return List.copyOf(all);
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    public long totalAdded() {
        return totalAdded.get();
    }

    /**
     * 按条件查询审计记录。
     *
     * @param actions    只保留这些动作；null 或空表示不按动作筛选
     * @param sinceMillis 只保留时间戳 &gt;= 该值的记录；null 表示不限制起点
     * @param untilMillis 只保留时间戳 &lt;= 该值的记录；null 表示不限制终点
     * @param limit      返回条数上限（&lt;=0 时取默认容量），最新记录优先
     * @return 一页结果（时间倒序），含总匹配数与截断提示
     */
    public AuditPage query(Set<AuditRecord.Action> actions, Long sinceMillis, Long untilMillis, int limit) {
        int effLimit = (limit <= 0) ? capacity : Math.min(limit, capacity);
        List<AuditRecord> matched = new ArrayList<>();
        lock.lock();
        try {
            long dropped = Math.max(0, totalAdded.get() - size);
            long oldestSeqKept = size == 0 ? -1 : ring[(head - size + capacity) % capacity].seq();
            // 从最新向最旧遍历
            for (int i = 1; i <= size; i++) {
                AuditRecord r = ring[(head - i + capacity) % capacity];
                if (actions != null && !actions.isEmpty() && !actions.contains(r.action())) {
                    continue;
                }
                if (sinceMillis != null && r.timestampMillis() < sinceMillis) {
                    continue;
                }
                if (untilMillis != null && r.timestampMillis() > untilMillis) {
                    continue;
                }
                matched.add(r);
            }
            int totalMatched = matched.size();
            boolean truncated = totalMatched > effLimit;
            List<AuditRecord> page = truncated ? List.copyOf(matched.subList(0, effLimit)) : List.copyOf(matched);
            return new AuditPage(page, page.size(), totalMatched, effLimit, truncated,
                    (int) Math.min(Integer.MAX_VALUE, dropped), oldestSeqKept);
        } finally {
            lock.unlock();
        }
    }
}
