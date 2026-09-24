package com.github.lisama569.featureflagrolloutjava.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 线程安全的审计日志，记录每次变更的来源、版本与结果。 */
public class AuditLog {
    private final AtomicLong seq = new AtomicLong(0);
    private final CopyOnWriteArrayList<AuditRecord> records = new CopyOnWriteArrayList<>();

    public AuditRecord append(String changeId, String source, long fromVersion, long toVersion,
                              AuditRecord.Action action, String detail) {
        AuditRecord record = new AuditRecord(seq.incrementAndGet(), changeId, source,
                fromVersion, toVersion, action, detail, System.currentTimeMillis());
        records.add(record);
        return record;
    }

    public List<AuditRecord> records() {
        return List.copyOf(records);
    }
}
