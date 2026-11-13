package com.icu.smart_cold_storage_monitor.config;

import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.security.JsonLoginHandler;
import com.icu.smart_cold_storage_monitor.security.JsonLogoutSuccessHandler;
import com.icu.smart_cold_storage_monitor.security.JsonSecurityErrorHandler;
import com.icu.smart_cold_storage_monitor.security.JsonSessionExpiredStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Browser authentication: server-side sessions, BCrypt password hashes, and CSRF protection on every state-changing
 * request including login and logout.
 *
 * <ul>
 *   <li>{@code GET  /api/auth/csrf}   public; returns the CSRF token (stored in the HTTP session).</li>
 *   <li>{@code POST /api/auth/login}  public + CSRF; form fields {@code username} and {@code password};
 *       200 JSON on success, 401 JSON on failure. The session id is rotated and a new CSRF token is issued.</li>
 *   <li>{@code POST /api/auth/logout} signed in + CSRF; invalidates the session; 204.</li>
 *   <li>{@code /api/users/**} and {@code /api/audit-events/**} administrators only.</li>
 *   <li>Every other {@code /api/**} path requires a signed-in user.</li>
 * </ul>
 *
 * Sessions are tracked in a {@link SessionRegistry} so a password reset or account deactivation can end the
 * affected user's sessions immediately.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    public static final String LOGIN_URL = "/api/auth/login";
    public static final String LOGOUT_URL = "/api/auth/logout";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonLoginHandler loginHandler,
                                                   JsonLogoutSuccessHandler logoutHandler,
                                                   JsonSecurityErrorHandler errorHandler,
                                                   JsonSessionExpiredStrategy sessionExpiredStrategy,
                                                   SessionRegistry sessionRegistry) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, LOGIN_URL).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/users/**", "/api/audit-events/**").hasRole(Role.ADMIN.name())
                        // Operators may set thresholds (as requests) and cancel their own requests; only an
                        // administrator reviews them.
                        .requestMatchers(HttpMethod.POST, "/api/settings/threshold-requests/*/approval",
                                "/api/settings/threshold-requests/*/rejection").hasRole(Role.ADMIN.name())
                        .requestMatchers("/api/**").authenticated()
                        // Non-API paths will serve the built frontend assets.
                        .anyRequest().permitAll())
                // CSRF stays enabled with the default session-backed token repository.
                .formLogin(form -> form
                        // A custom login "page" disables Spring's generated HTML login and logout pages.
                        .loginPage(LOGIN_URL)
                        .loginProcessingUrl(LOGIN_URL)
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(loginHandler)
                        .failureHandler(loginHandler))
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_URL)
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(logoutHandler))
                .sessionManagement(session -> session
                        .maximumSessions(-1) // no limit; enables registry tracking and expiry checks
                        .sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(sessionExpiredStrategy))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .requestCache(RequestCacheConfigurer::disable)
                .httpBasic(basic -> basic.disable());
        return http.build();
    }

    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Keeps the session registry in sync when the servlet container destroys or re-identifies sessions. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
