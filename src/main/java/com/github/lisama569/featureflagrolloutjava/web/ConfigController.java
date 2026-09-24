package com.github.lisama569.featureflagrolloutjava.web;

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

import java.util.List;
import java.util.Map;

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

    @GetMapping("/audit")
    public List<AuditRecord> audit() {
        return configService.auditLog().records();
    }
}
