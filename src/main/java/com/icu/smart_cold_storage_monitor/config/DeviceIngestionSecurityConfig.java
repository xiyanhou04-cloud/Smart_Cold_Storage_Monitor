package com.icu.smart_cold_storage_monitor.config;

import com.icu.smart_cold_storage_monitor.repository.DeviceRepository;
import com.icu.smart_cold_storage_monitor.security.DeviceAuthenticationFilter;
import com.icu.smart_cold_storage_monitor.security.DeviceCredentialService;
import com.icu.smart_cold_storage_monitor.security.JsonSecurityErrorHandler;
import com.icu.smart_cold_storage_monitor.web.JsonResponseWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Security for device ingestion: a separate filter chain that matches only {@code POST /api/readings}. It is
 * stateless, accepts only the device credential header (never the browser session cookie), and is exempt from
 * CSRF because no browser credential is involved. Ordered before the catch-all browser chain in
 * {@link SecurityConfig} and after the monitoring prototype's {@code @Order(1)} chain.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IngestionProperties.class, SimulatorProperties.class})
public class DeviceIngestionSecurityConfig {

    public static final String READINGS_URL = "/api/readings";

    @Bean
    @Order(2)
    public SecurityFilterChain deviceIngestionFilterChain(HttpSecurity http, DeviceCredentialService credentials,
                                                          DeviceRepository devices, JsonResponseWriter writer,
                                                          JsonSecurityErrorHandler errorHandler) throws Exception {
        return http
                .securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, READINGS_URL))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Only this request's own credential counts; a browser session is never loaded.
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new DeviceAuthenticationFilter(credentials, devices, writer), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(DeviceAuthenticationFilter.ROLE))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .build();
    }
}
