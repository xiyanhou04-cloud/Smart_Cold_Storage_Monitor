package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.User;
import java.time.Instant;

/** Public view of an account; never contains the password hash. */
public record UserResponse(long id, String username, Role role, boolean enabled, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.id(), user.username(), user.role(), user.enabled(), user.createdAt());
    }
}
