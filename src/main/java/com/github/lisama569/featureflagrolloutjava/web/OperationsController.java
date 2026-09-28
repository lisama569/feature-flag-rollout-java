package com.github.lisama569.featureflagrolloutjava.web;

import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.operations.OperationsService;
import com.github.lisama569.featureflagrolloutjava.operations.SystemOverview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.Set;

/**
 * 值班运行总览与诊断入口。
 * 支持按结果（成功/拒绝/回滚）与时间窗筛选，返回带容量上限与截断提示。
 */
@RestController
@RequestMapping("/api/operations")
public class OperationsController {

    private final OperationsService operationsService;

    public OperationsController(OperationsService operationsService) {
        this.operationsService = operationsService;
    }

    /**
     * 当前运行总览。
     *
     * @param outcomes 逗号分隔，只看 APPLIED/REJECTED/ROLLED_BACK；缺省全部
     * @param since    毫秒时间戳，只看该时间之后的活动；可空
     * @param until    毫秒时间戳，只看该时间之前的活动；可空
     * @param limit    活动返回上限，缺省 20
     */
    @GetMapping("/overview")
    public SystemOverview overview(@RequestParam(required = false) String outcomes,
                                   @RequestParam(required = false) Long since,
                                   @RequestParam(required = false) Long until,
                                   @RequestParam(required = false) Integer limit) {
        Set<AuditRecord.Action> actionFilter = parseOutcomes(outcomes);
        return operationsService.overview(actionFilter, since, until, limit);
    }

    private Set<AuditRecord.Action> parseOutcomes(String outcomes) {
        if (outcomes == null || outcomes.isBlank()) {
            return null;
        }
        Set<AuditRecord.Action> set = EnumSet.noneOf(AuditRecord.Action.class);
        for (String part : outcomes.split(",")) {
            String token = part.trim().toUpperCase();
            if (!token.isEmpty()) {
                try {
                    set.add(AuditRecord.Action.valueOf(token));
                } catch (IllegalArgumentException ignore) {
                    // 忽略无法识别的结果标记，避免错误入参让整个总览不可用
                }
            }
        }
        return set.isEmpty() ? null : set;
    }
}
