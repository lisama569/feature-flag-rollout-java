package com.github.lisama569.featureflagrolloutjava.audit;

import com.github.lisama569.featureflagrolloutjava.domain.AuditRecord;

import java.util.List;

/**
 * 变更审计日志：记录每次变更的来源与版本。
 */
public interface AuditLog {
    void append(AuditRecord record);

    List<AuditRecord> all();
}
