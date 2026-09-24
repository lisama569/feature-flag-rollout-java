package com.github.lisama569.featureflagrolloutjava.web;

import com.github.lisama569.featureflagrolloutjava.rollout.Decision;
import com.github.lisama569.featureflagrolloutjava.rollout.FeatureFlagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 特性开关判定入口，返回可解释的判定结果（输入、判定依据、最终结果）。 */
@RestController
@RequestMapping("/api/flags")
public class FlagController {

    private final FeatureFlagService featureFlagService;

    public FlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @GetMapping("/evaluate")
    public Decision evaluate(@RequestParam String flag, @RequestParam String subject) {
        return featureFlagService.evaluate(flag, subject);
    }
}
