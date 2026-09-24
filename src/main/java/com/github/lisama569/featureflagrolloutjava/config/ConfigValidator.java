package com.github.lisama569.featureflagrolloutjava.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置项校验器，按 key 后缀约定校验取值合法性：
 * <ul>
 *   <li>{@code *.percentage}：必须是 [0,100] 的整数（灰度比例）</li>
 *   <li>{@code *.enabled}：必须是 true/false（开关）</li>
 *   <li>其余 key：仅要求 key 非空白、value 非 null</li>
 * </ul>
 * 每个非法项都会得到可区分的 {@link ViolationCode}。
 */
public class ConfigValidator {

    public List<ValidationError> validate(Map<String, String> entries) {
        List<ValidationError> errors = new ArrayList<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || key.isBlank()) {
                errors.add(new ValidationError(key, value, ViolationCode.EMPTY_KEY, "配置键不能为空"));
                continue;
            }
            if (value == null) {
                errors.add(new ValidationError(key, null, ViolationCode.NULL_VALUE, "配置值不能为 null"));
                continue;
            }
            if (key.endsWith(".percentage")) {
                validatePercentage(key, value, errors);
            } else if (key.endsWith(".enabled")) {
                validateBoolean(key, value, errors);
            }
        }
        return List.copyOf(errors);
    }

    private void validatePercentage(String key, String value, List<ValidationError> errors) {
        final int v;
        try {
            v = Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            errors.add(new ValidationError(key, value, ViolationCode.NOT_AN_INTEGER,
                    "灰度比例必须是整数: " + value));
            return;
        }
        if (v < 0 || v > 100) {
            errors.add(new ValidationError(key, value, ViolationCode.OUT_OF_RANGE,
                    "灰度比例必须在 [0,100] 之间: " + v));
        }
    }

    private void validateBoolean(String key, String value, List<ValidationError> errors) {
        String v = value.trim();
        if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
            errors.add(new ValidationError(key, value, ViolationCode.NOT_A_BOOLEAN,
                    "开关取值必须是 true/false: " + value));
        }
    }
}
