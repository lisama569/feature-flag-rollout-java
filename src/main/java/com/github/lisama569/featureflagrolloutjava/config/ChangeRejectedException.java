package com.github.lisama569.featureflagrolloutjava.config;

import java.util.List;

/** 变更被拒绝时抛出，携带可区分的拒绝原因与校验明细。 */
public class ChangeRejectedException extends Exception {
    private final RejectionReason reason;
    private final List<ValidationError> errors;

    public ChangeRejectedException(RejectionReason reason, String message) {
        this(reason, message, List.of());
    }

    public ChangeRejectedException(RejectionReason reason, String message, List<ValidationError> errors) {
        super(message);
        this.reason = reason;
        this.errors = List.copyOf(errors);
    }

    public RejectionReason reason() { return reason; }

    public List<ValidationError> errors() { return errors; }
}
