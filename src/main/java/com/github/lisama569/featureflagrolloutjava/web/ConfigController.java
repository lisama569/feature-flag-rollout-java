package com.github.lisama569.featureflagrolloutjava.web;

import com.github.lisama569.featureflagrolloutjava.config.AuditPage;
import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 配置查询、热更新、变更提交与回滚的 REST 入口。 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ConfigSnapshot current() {
        return configService.currentSnapshot();
    }

    @PostMapping("/refresh")
    public ConfigSnapshot refresh() {
        return configService.refresh();
    }

    /** 兼容旧调用方的单项变更提交入口。 */
    @PostMapping("/changes")
    public ResponseEntity<?> submit(@RequestBody ChangeRequest request) {
        try {
            return ResponseEntity.ok(configService.submitChange(request));
        } catch (ChangeRejectedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "reason", e.reason().name(),
                    "message", e.getMessage(),
                    "errors", e.errors()));
        }
    }

    @PostMapping("/rollback/{version}")
    public ConfigSnapshot rollback(@PathVariable long version,
                                   @RequestParam(defaultValue = "api") String operator) {
        return configService.rollback(version, operator);
    }

    /** 兼容旧调用方：返回缓冲内全部审计记录（正序）。 */
    @GetMapping("/audit")
    public List<AuditRecord> audit() {
        return configService.auditLog().records();
    }

    /**
     * 有容量上限、可筛选的审计查询（最新在前）。
     *
     * @param outcomes 逗号分隔：APPLIED/REJECTED/ROLLED_BACK；缺省全部
     * @param since    起始毫秒时间戳（含）；可空
     * @param until    截止毫秒时间戳（含）；可空
     * @param limit    返回上限，缺省取审计容量
     */
    @GetMapping("/audit/query")
    public AuditPage auditQuery(@RequestParam(required = false) String outcomes,
                                @RequestParam(required = false) Long since,
                                @RequestParam(required = false) Long until,
                                @RequestParam(required = false) Integer limit) {
        Set<AuditRecord.Action> filter = null;
        if (outcomes != null && !outcomes.isBlank()) {
            filter = EnumSet.noneOf(AuditRecord.Action.class);
            for (String part : outcomes.split(",")) {
                try {
                    filter.add(AuditRecord.Action.valueOf(part.trim().toUpperCase()));
                } catch (IllegalArgumentException ignore) {
                    // 忽略无法识别的标记
                }
            }
            if (filter.isEmpty()) {
                filter = null;
            }
        }
        return configService.auditLog().query(filter, since, until, limit == null ? 0 : limit);
    }
}
