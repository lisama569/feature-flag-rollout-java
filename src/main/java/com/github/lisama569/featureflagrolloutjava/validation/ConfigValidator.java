package com.github.lisama569.featureflagrolloutjava.validation;

import com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry;
import com.github.lisama569.featureflagrolloutjava.domain.ValidationIssue;

import java.util.List;

/**
 * 校验单个来源提供的条目；非法条目被拒绝并给出可区分原因，合法条目原样返回。
 */
public interface ConfigValidator {
    record Result(List<ConfigEntry> validEntries, List<ValidationIssue> issues) {
    }

    Result validate(String sourceName, List<? extends ConfigEntry> entries);
}
