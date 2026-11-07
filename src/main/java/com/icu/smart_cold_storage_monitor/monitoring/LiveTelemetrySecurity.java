package com.icu.smart_cold_storage_monitor.monitoring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("monitoring-live")
class LiveTelemetrySecurity {
    @Bean
    @Order(1)
    SecurityFilterChain telemetryDeviceChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher(PathPatternRequestMatcher.withDefaults()
                .matcher(HttpMethod.POST, "/api/telemetry/readings"))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
            .build();
    }
}
