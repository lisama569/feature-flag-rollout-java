package com.github.lisama569.featureflagrolloutjava.web;

import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.ops.AuditPage;
import com.github.lisama569.featureflagrolloutjava.ops.OperationsOverview;
import com.github.lisama569.featureflagrolloutjava.ops.OperationsOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 值班总览与诊断入口：
 * GET /api/ops/overview                       当前版本、来源降级、近期动作、打开的开关
 * GET /api/ops/audit?action=&from=&to=&limit= 有界、可筛选的审计查询
 */
@RestController
@RequestMapping("/api/ops")
public class OperationsController {

    private final OperationsOverviewService overviewService;

    public OperationsController(OperationsOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/overview")
    public OperationsOverview overview() {
        return overviewService.overview();
    }

    @GetMapping("/audit")
    public AuditPage audit(@RequestParam(required = false) AuditRecord.Action action,
                           @RequestParam(required = false) Long from,
                           @RequestParam(required = false) Long to,
                           @RequestParam(required = false) Integer limit) {
        return overviewService.queryAudit(action, from, to, limit);
    }
}
