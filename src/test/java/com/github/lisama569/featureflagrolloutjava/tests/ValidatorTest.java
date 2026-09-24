package com.github.lisama569.featureflagrolloutjava.tests;

import com.github.lisama569.featureflagrolloutjava.domain.FlagEntry;
import com.github.lisama569.featureflagrolloutjava.domain.PropertyEntry;
import com.github.lisama569.featureflagrolloutjava.domain.RejectReason;
import com.github.lisama569.featureflagrolloutjava.domain.ValidationIssue;
import com.github.lisama569.featureflagrolloutjava.validation.ConfigValidator;
import com.github.lisama569.featureflagrolloutjava.validation.StrictConfigValidator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验器单测：不同非法输入必须被拒绝并给出可区分原因；合法条目原样保留。
 */
class ValidatorTest {

    private static final Logger log = LoggerFactory.getLogger(ValidatorTest.class);

    private final ConfigValidator validator = new StrictConfigValidator();

    @Test
    void rejectPercentageOutOfRangeWithDistinctReason() {
        var entries = List.<com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry>of(
                new FlagEntry("f1", true, 120, false, "exp"));
        ConfigValidator.Result result = validator.validate("remote", entries);
        log.info("输入: {} | 判定依据: 放量比例 120 超出 [0,100] | 结果: valid={}, issues={}",
                entries, result.validEntries().size(), result.issues());
        assertTrue(result.validEntries().isEmpty());
        ValidationIssue issue = result.issues().get(0);
        assertEquals(RejectReason.INVALID_FLAG_PERCENTAGE, issue.reason());
        assertEquals("f1", issue.entryKey());
    }

    @Test
    void rejectBlankVariantKeyWithDistinctReason() {
        var entries = List.<com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry>of(
                new FlagEntry("f2", true, 50, false, "  "));
        ConfigValidator.Result result = validator.validate("remote", entries);
        log.info("输入: flag=f2, variant='  ' | 判定依据: 变体键空白 | 结果: reason={}",
                result.issues().get(0).reason());
        assertEquals(RejectReason.INVALID_FLAG_TYPE, result.issues().get(0).reason());
    }

    @Test
    void rejectBlankPropertyValueWithDistinctReason() {
        var entries = List.<com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry>of(
                new PropertyEntry("p1", ""));
        ConfigValidator.Result result = validator.validate("local", entries);
        log.info("输入: property=p1, value='' | 判定依据: 属性值空白 | 结果: reason={}",
                result.issues().get(0).reason());
        assertEquals(RejectReason.INVALID_PROPERTY_BLANK, result.issues().get(0).reason());
    }

    @Test
    void rejectDuplicateKeysAsInvalidPayload() {
        var entries = List.<com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry>of(
                new PropertyEntry("dup", "a"),
                new PropertyEntry("dup", "b"));
        ConfigValidator.Result result = validator.validate("local", entries);
        log.info("输入: 同来源两个 dup 键 | 判定依据: 来源内键重复 | 结果: valid={}, reasons={}",
                result.validEntries().size(), result.issues().stream().map(ValidationIssue::reason).toList());
        assertEquals(1, result.validEntries().size());
        assertEquals(RejectReason.INVALID_PAYLOAD, result.issues().get(0).reason());
    }

    @Test
    void keepValidEntriesUntouched() {
        var entries = List.<com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry>of(
                new FlagEntry("ok-flag", true, 30, false, "exp"),
                new PropertyEntry("ok-prop", "v1"));
        ConfigValidator.Result result = validator.validate("remote", entries);
        log.info("输入: 全部合法 | 判定依据: 逐条校验通过 | 结果: valid={}, issues={}",
                result.validEntries().size(), result.issues().size());
        assertEquals(2, result.validEntries().size());
        assertTrue(result.issues().isEmpty());
    }
}
