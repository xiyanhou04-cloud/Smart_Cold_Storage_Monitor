package com.icu.smart_cold_storage_monitor.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.service.UserAccountService.AccountCredentials;
import java.time.Instant;

/**
 * Response of account creation and password reset. {@code generatedPassword} is present only when the server
 * generated the password; it is returned this one time and cannot be retrieved later.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CredentialResponse(long id, String username, Role role, boolean enabled, Instant createdAt,
                                 String generatedPassword) {

    public static CredentialResponse from(AccountCredentials credentials) {
        var user = credentials.user();
        return new CredentialResponse(user.id(), user.username(), user.role(), user.enabled(), user.createdAt(),
                credentials.generatedPassword());
    }
}
