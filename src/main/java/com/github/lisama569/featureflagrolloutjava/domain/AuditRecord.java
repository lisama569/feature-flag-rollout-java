package com.github.lisama569.featureflagrolloutjava.domain;

import java.time.Instant;

/**
 * 一次配置变更的审计记录。
 */
public record AuditRecord(Instant timestamp, AuditKind kind, String sourceName,
                          Long fromVersion, Long toVersion, String detail,
                          long resultingConfigVersion) {
}
