package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.security.AppUserPrincipal;

/** Body of a successful login and of {@code GET /api/auth/me}. */
public record CurrentUserResponse(String username, Role role) {

    public static CurrentUserResponse from(AppUserPrincipal principal) {
        return new CurrentUserResponse(principal.getUsername(), principal.getRole());
    }
}
