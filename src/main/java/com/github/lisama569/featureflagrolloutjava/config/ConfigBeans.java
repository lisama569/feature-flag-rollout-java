package com.github.lisama569.featureflagrolloutjava.config;

import com.github.lisama569.featureflagrolloutjava.operations.OperationsService;
import com.github.lisama569.featureflagrolloutjava.release.ReleaseService;
import com.github.lisama569.featureflagrolloutjava.rollout.FeatureFlagService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 装配配置中心、特性开关、发布编排与值班总览相关 Bean。
 * 默认提供两个来源：classpath 内置默认（低优先级）与内存动态来源（高优先级，可热更新）。
 */
@Configuration
public class ConfigBeans {

    @Bean
    public InMemoryConfigSource defaultsSource() {
        InMemoryConfigSource source = new InMemoryConfigSource("defaults", 0);
        source.setEntries(Map.of(
                "feature.new-checkout.enabled", "false",
                "feature.new-checkout.percentage", "0"));
        return source;
    }

    @Bean
    public InMemoryConfigSource dynamicSource() {
        return new InMemoryConfigSource("dynamic", 100);
    }

    @Bean
    public AuditLog auditLog() {
        return new AuditLog();
    }

    @Bean
    public ConfigService configService(List<ConfigSource> sources, AuditLog auditLog) {
        return new ConfigService(sources, new ConfigValidator(), auditLog);
    }

    @Bean
    public FeatureFlagService featureFlagService(ConfigService configService) {
        return new FeatureFlagService(configService);
    }

    @Bean
    public ReleaseService releaseService(ConfigService configService) {
        return new ReleaseService(configService);
    }

    @Bean
    public OperationsService operationsService(ConfigService configService) {
        return new OperationsService(configService);
    }
}
