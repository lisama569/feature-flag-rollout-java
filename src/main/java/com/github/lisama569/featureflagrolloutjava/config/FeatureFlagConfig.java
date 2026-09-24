package com.github.lisama569.featureflagrolloutjava.config;

import com.github.lisama569.featureflagrolloutjava.domain.ConfigEntry;
import com.github.lisama569.featureflagrolloutjava.domain.FlagEntry;
import com.github.lisama569.featureflagrolloutjava.source.ConfigSource;
import com.github.lisama569.featureflagrolloutjava.source.MapConfigSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * 默认装配：两个内存配置来源演示分层（remote 优先级高于 local-defaults）。
 */
@Configuration
@EnableScheduling
public class FeatureFlagConfig {

    @Bean
    public ConfigSource localDefaultsSource() {
        List<ConfigEntry> bootstrap = List.of(
                new FlagEntry("new-checkout", false, 0, false, "checkout-exp"),
                new FlagEntry("dark-mode", true, 50, false, "ui-exp"));
        return new MapConfigSource("local-defaults", 0, bootstrap);
    }

    @Bean
    public ConfigSource remoteSource() {
        return new MapConfigSource("remote", 100, List.of());
    }
}
