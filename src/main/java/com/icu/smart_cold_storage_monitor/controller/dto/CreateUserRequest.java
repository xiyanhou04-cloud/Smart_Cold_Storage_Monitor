package com.icu.smart_cold_storage_monitor.controller.dto;

import com.icu.smart_cold_storage_monitor.model.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/users}. Omit {@code password} (or send it empty) to have one generated.
 * Detailed username and password rules are enforced by the service.
 */
public record CreateUserRequest(
        @NotBlank @Size(max = 64) String username,
        @NotNull Role role,
        @Size(max = 200) String password) {
}
