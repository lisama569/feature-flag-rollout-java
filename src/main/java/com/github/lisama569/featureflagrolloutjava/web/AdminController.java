package com.github.lisama569.featureflagrolloutjava.web;

import com.github.lisama569.featureflagrolloutjava.audit.AuditLog;
import com.github.lisama569.featureflagrolloutjava.audit.SnapshotHistory;
import com.github.lisama569.featureflagrolloutjava.domain.AuditRecord;
import com.github.lisama569.featureflagrolloutjava.domain.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.domain.EvaluationResult;
import com.github.lisama569.featureflagrolloutjava.domain.MergedConfig;
import com.github.lisama569.featureflagrolloutjava.service.FeatureFlagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理面 HTTP API。
 */
@RestController
@RequestMapping("/api/feature-flags")
public class AdminController {

    private final FeatureFlagService service;
    private final AuditLog auditLog;
    private final SnapshotHistory history;

    public AdminController(FeatureFlagService service, AuditLog auditLog, SnapshotHistory history) {
        this.service = service;
        this.auditLog = auditLog;
        this.history = history;
    }

    /** 手动触发一次热更新。 */
    @PostMapping("/reload")
    public MergedConfig reload() {
        return service.reload();
    }

    /** 判定开关（含判定依据）。 */
    @GetMapping("/{key}/evaluate")
    public EvaluationResult evaluate(@PathVariable String key, @RequestParam String subject) {
        return service.evaluate(key, subject);
    }

    /** 读取普通属性生效值。 */
    @GetMapping("/properties/{key}")
    public Map<String, Object> getProperty(@PathVariable String key) {
        return service.getProperty(key)
                .map(v -> Map.<String, Object>of("key", key, "value", v,
                        "configVersion", service.current().version()))
                .orElseGet(() -> Map.of("key", key, "present", false));
    }

    /** 提交变更（乐观版本 + 幂等键）。 */
    @PostMapping("/changes")
    public MergedConfig submit(@RequestBody ChangeRequest request) {
        return service.submit(request);
    }

    /** 回滚到指定历史配置版本。 */
    @PostMapping("/rollback/{version}")
    public MergedConfig rollback(@PathVariable long version) {
        return service.rollback(version);
    }

    /** 当前生效快照。 */
    @GetMapping("/current")
    public MergedConfig current() {
        return service.current();
    }

    /** 历史版本列表。 */
    @GetMapping("/versions")
    public List<MergedConfig> versions() {
        return history.versions();
    }

    /** 审计记录。 */
    @GetMapping("/audit")
    public List<AuditRecord> audit() {
        return auditLog.all();
    }
}
