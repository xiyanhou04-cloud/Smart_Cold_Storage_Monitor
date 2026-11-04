package com.icu.smart_cold_storage_monitor.controller;

import com.icu.smart_cold_storage_monitor.controller.dto.CsrfTokenResponse;
import com.icu.smart_cold_storage_monitor.controller.dto.CurrentUserResponse;
import com.icu.smart_cold_storage_monitor.security.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only authentication endpoints. {@code POST /api/auth/login} and {@code POST /api/auth/logout} are handled
 * by the Spring Security filter chain (see {@code SecurityConfig}) so that session fixation protection, CSRF token
 * rotation, and session invalidation use the framework's implementations.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Returns the CSRF token the browser must send with login, logout, and every other mutation. */
    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken token) {
        return new CsrfTokenResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    /** Returns the signed-in user's username and role. */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return CurrentUserResponse.from(principal);
    }
}
