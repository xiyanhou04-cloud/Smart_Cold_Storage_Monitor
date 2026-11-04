package com.icu.smart_cold_storage_monitor.controller.dto;

import jakarta.validation.constraints.Size;

/** Optional body of {@code POST /api/users/{id}/password-reset}. Omit {@code newPassword} to have one generated. */
public record ResetPasswordRequest(@Size(max = 200) String newPassword) {
}
