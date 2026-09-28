package com.github.lisama569.featureflagrolloutjava.web;

import com.github.lisama569.featureflagrolloutjava.config.BatchChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.release.PreflightResult;
import com.github.lisama569.featureflagrolloutjava.release.ReleaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 发布前试算与批次原子发布入口。
 * 试算只读，永不改变生效配置；发布为整批原子。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    /** 发布前试算：返回预计结果、逐项拒绝原因、受影响开关与灰度变化；HTTP 始终 200，结论看 body.status。 */
    @PostMapping("/preflight")
    public PreflightResult preflight(@RequestBody BatchChangeRequest request) {
        return releaseService.preflight(request);
    }

    /** 批次原子发布：整批生效或整批保持原样；拒绝时 409 并给出可区分原因。 */
    @PostMapping("/batch")
    public ResponseEntity<?> publishBatch(@RequestBody BatchChangeRequest request) {
        try {
            return ResponseEntity.ok(releaseService.publishBatch(request));
        } catch (ChangeRejectedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "reason", e.reason().name(),
                    "message", e.getMessage(),
                    "errors", e.errors()));
        }
    }
}
