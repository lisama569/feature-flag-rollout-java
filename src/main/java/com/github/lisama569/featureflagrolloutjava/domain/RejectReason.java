package com.github.lisama569.featureflagrolloutjava.domain;

/**
 * 变更/配置项被拒绝的可区分原因。
 */
public enum RejectReason {
    INVALID_PAYLOAD,
    INVALID_FLAG_PERCENTAGE,
    INVALID_FLAG_TYPE,
    INVALID_PROPERTY_BLANK,
    UNKNOWN_ENTRY_TYPE,
    STALE_VERSION,
    DUPLICATE_SUBMISSION,
    NO_OP_CHANGE,
    UNKNOWN_SOURCE,
    SOURCE_UNAVAILABLE
}
