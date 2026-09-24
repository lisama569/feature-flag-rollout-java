package com.github.lisama569.featureflagrolloutjava.validation;

import com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry;
import com.github.lisama569.featureflagrolloutjava.domain.FlagEntry;
import com.github.lisama569.featureflagrolloutjava.domain.PropertyEntry;
import com.github.lisama569.featureflagrolloutjava.domain.RejectReason;
import com.github.lisama569.featureflagrolloutjava.domain.ValidationIssue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 逐条严格校验：
 * <ul>
 *   <li>条目键为空 → {@link RejectReason#INVALID_PAYLOAD}；</li>
 *   <li>同一来源内键重复 → {@link RejectReason#INVALID_PAYLOAD}；</li>
 *   <li>特性开关放量比例不在 [0,100] → {@link RejectReason#INVALID_FLAG_PERCENTAGE}；</li>
 *   <li>开关实验变体键为空 → {@link RejectReason#INVALID_FLAG_TYPE}；</li>
 *   <li>属性值为空/空白 → {@link RejectReason#INVALID_PROPERTY_BLANK}；</li>
 *   <li>未知条目类型 → {@link RejectReason#UNKNOWN_ENTRY_TYPE}。</li>
 * </ul>
 * 非法条目被剔除并携带原因返回，绝不参与合并，因此不会静默覆盖已生效取值。
 */
@Component
public class StrictConfigValidator implements ConfigValidator {

    @Override
    public Result validate(String sourceName, List<? extends ConfigEntry> entries) {
        List<ConfigEntry> valid = new ArrayList<>();
        List<ValidationIssue> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        if (entries == null) {
            issues.add(new ValidationIssue(sourceName, null, RejectReason.INVALID_PAYLOAD,
                    "entry list is null"));
            return new Result(List.of(), List.copyOf(issues));
        }

        for (ConfigEntry entry : entries) {
            String key = entry == null ? null : entry.key();
            if (entry == null) {
                issues.add(new ValidationIssue(sourceName, null, RejectReason.INVALID_PAYLOAD,
                        "entry is null"));
                continue;
            }
            if (key == null || key.isBlank()) {
                issues.add(new ValidationIssue(sourceName, key, RejectReason.INVALID_PAYLOAD,
                        "entry key must not be blank"));
                continue;
            }
            if (!seen.add(key)) {
                issues.add(new ValidationIssue(sourceName, key, RejectReason.INVALID_PAYLOAD,
                        "duplicate key within source '" + sourceName + "'"));
                continue;
            }
            String typeError = checkTyped(entry, sourceName);
            if (typeError != null) {
                issues.add(new ValidationIssue(sourceName, key, reasonOf(entry), typeError));
                continue;
            }
            valid.add(entry);
        }
        return new Result(List.copyOf(valid), List.copyOf(issues));
    }

    private String checkTyped(ConfigEntry entry, String sourceName) {
        if (entry instanceof FlagEntry flag) {
            if (flag.rolloutPercentage() < 0 || flag.rolloutPercentage() > 100) {
                return "rolloutPercentage must be within [0,100] but was " + flag.rolloutPercentage();
            }
            if (flag.variantKey() == null || flag.variantKey().isBlank()) {
                return "variantKey must not be blank for flag '" + flag.key() + "'";
            }
            return null;
        }
        if (entry instanceof PropertyEntry prop) {
            if (prop.value() == null || prop.value().isBlank()) {
                return "property value must not be blank for key '" + prop.key() + "'";
            }
            return null;
        }
        return "unsupported config entry type: " + entry.getClass().getName();
    }

    private RejectReason reasonOf(ConfigEntry entry) {
        if (entry instanceof FlagEntry flag) {
            if (flag.rolloutPercentage() < 0 || flag.rolloutPercentage() > 100) {
                return RejectReason.INVALID_FLAG_PERCENTAGE;
            }
            return RejectReason.INVALID_FLAG_TYPE;
        }
        if (entry instanceof PropertyEntry) {
            return RejectReason.INVALID_PROPERTY_BLANK;
        }
        return RejectReason.UNKNOWN_ENTRY_TYPE;
    }
}
