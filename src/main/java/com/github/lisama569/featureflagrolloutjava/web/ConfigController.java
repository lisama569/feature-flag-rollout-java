package com.github.lisama569.featureflagrolloutjava.web;

import com.github.lisama569.featureflagrolloutjava.config.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import com.github.lisama569.featureflagrolloutjava.config.HistoryRetentionStatus;
import com.github.lisama569.featureflagrolloutjava.config.VersionEvictedException;
import com.github.lisama569.featureflagrolloutjava.config.VersionNotFoundException;
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
    public ResponseEntity<?> rollback(@PathVariable long version,
                                      @RequestParam(defaultValue = "api") String operator) {
        try {
            return ResponseEntity.ok(configService.rollback(version, operator));
        } catch (VersionEvictedException e) {
            // 目标版本曾存在但已被保留上限淘汰：明确告知不可用与仍可回滚的最早版本，HTTP 410
            return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                    "reason", "VERSION_EVICTED",
                    "version", e.version(),
                    "retentionLimit", e.retentionLimit(),
                    "oldestRetainedVersion", e.oldestRetainedVersion(),
                    "message", e.getMessage()));
        } catch (VersionNotFoundException e) {
            // 目标版本从未存在：HTTP 404
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "reason", "VERSION_NOT_FOUND",
                    "version", e.version(),
                    "currentVersion", e.currentVersion(),
                    "message", e.getMessage()));
        }
    }

    /** 可回滚版本历史的保留状况：上限、当前保留版本、已淘汰计数与最近淘汰版本。 */
    @GetMapping("/history")
    public HistoryRetentionStatus history() {
        return configService.historyRetentionStatus();
    }

    @GetMapping("/audit")
    public List<AuditRecord> audit() {
        return configService.auditLog().records();
    }
}
