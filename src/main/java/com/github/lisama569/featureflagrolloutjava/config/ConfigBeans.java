package com.github.lisama569.featureflagrolloutjava.config;

import com.github.lisama569.featureflagrolloutjava.ops.OperationsOverviewService;
import com.github.lisama569.featureflagrolloutjava.rollout.FeatureFlagService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 装配配置中心与特性开关相关 Bean。
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
    public ConfigService configService(List<ConfigSource> sources, AuditLog auditLog,
                                       @org.springframework.beans.factory.annotation.Value(
                                               "${feature-flag.history-retention-limit:500}") int historyRetentionLimit) {
        return new ConfigService(sources, new ConfigValidator(), auditLog, historyRetentionLimit);
    }

    @Bean
    public FeatureFlagService featureFlagService(ConfigService configService) {
        return new FeatureFlagService(configService);
    }

    @Bean
    public OperationsOverviewService operationsOverviewService(ConfigService configService) {
        return new OperationsOverviewService(configService);
    }
}
