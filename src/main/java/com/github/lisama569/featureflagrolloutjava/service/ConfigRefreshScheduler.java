package com.github.lisama569.featureflagrolloutjava.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性热更新触发器。任何异常都不允许逃出调度线程，
 * 且异常情况下 {@link FeatureFlagService#reload()} 自身会降级到最近可用快照。
 */
@Component
public class ConfigRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConfigRefreshScheduler.class);

    private final FeatureFlagService service;

    public ConfigRefreshScheduler(FeatureFlagService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${featureflag.refresh-ms:10000}")
    public void tick() {
        try {
            service.reload();
        } catch (Exception e) {
            log.error("周期性配置重载失败，继续沿用当前生效快照", e);
        }
    }
}
