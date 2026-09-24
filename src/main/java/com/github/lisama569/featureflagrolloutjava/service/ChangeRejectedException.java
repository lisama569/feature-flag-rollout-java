package com.github.lisama569.featureflagrolloutjava.service;

import com.github.lisama569.featureflagrolloutjava.domain.RejectReason;

/**
 * 变更被明确拒绝（过期版本 / 重复提交 / 非法内容等），原因可区分。
 */
public class ChangeRejectedException extends RuntimeException {

    private final RejectReason reason;

    public ChangeRejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
