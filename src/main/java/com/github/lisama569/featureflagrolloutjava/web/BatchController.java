package com.github.lisama569.featureflagrolloutjava.web;

import com.github.lisama569.featureflagrolloutjava.config.BatchPublishRequest;
import com.github.lisama569.featureflagrolloutjava.config.ChangeRejectedException;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.ConfigSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 批次原子发布入口：整批生效或整批拒绝（409 + 可区分原因）。 */
@RestController
@RequestMapping("/api/config")
public class BatchController {

    private final ConfigService configService;

    public BatchController(ConfigService configService) {
        this.configService = configService;
    }

    @PostMapping("/batch")
    public ResponseEntity<?> publish(@RequestBody BatchPublishRequest request) {
        try {
            return ResponseEntity.ok(configService.publishBatch(request));
        } catch (ChangeRejectedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "reason", e.reason().name(),
                    "message", e.getMessage(),
                    "errors", e.errors()));
        }
    }
}
