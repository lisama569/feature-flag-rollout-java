package com.github.lisama569.featureflagrolloutjava.config;

/** 变更被明确拒绝的可区分原因。 */
public enum RejectionReason {
    STALE_BASE_VERSION,
    DUPLICATE_CHANGE_ID,
    INVALID_ENTRIES
}
