package com.github.lisama569.featureflagrolloutjava.audit;

import com.github.lisama569.featureflagrolloutjava.domain.AuditRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存审计日志：追加式、线程安全，完整保留每次变更的来源与版本。
 */
@Component
public class InMemoryAuditLog implements AuditLog {

    private final CopyOnWriteArrayList<AuditRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void append(AuditRecord record) {
        records.add(record);
    }

    @Override
    public List<AuditRecord> all() {
        return List.copyOf(records);
    }
}
