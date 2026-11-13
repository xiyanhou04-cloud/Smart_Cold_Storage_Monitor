package com.icu.smart_cold_storage_monitor.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the default threshold limits ({@code app.thresholds.*}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ThresholdProperties.class)
public class ThresholdConfig {
}
