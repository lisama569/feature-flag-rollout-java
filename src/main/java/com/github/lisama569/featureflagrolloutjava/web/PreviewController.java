package com.github.lisama569.featureflagrolloutjava.web;

import com.github.lisama569.featureflagrolloutjava.config.ChangeRequest;
import com.github.lisama569.featureflagrolloutjava.config.ConfigService;
import com.github.lisama569.featureflagrolloutjava.config.PreviewResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发布前试算入口。试算永远返回 200（即使预计会被拒绝），因为它只报告“预计结果”，
 * 绝不改动生效配置、可回滚历史，也不占用真实发布的幂等位置。
 */
@RestController
@RequestMapping("/api/config")
public class PreviewController {

    private final ConfigService configService;

    public PreviewController(ConfigService configService) {
        this.configService = configService;
    }

    @PostMapping("/changes/preview")
    public PreviewResult preview(@RequestBody ChangeRequest request) {
        return configService.previewChange(request);
    }
}
