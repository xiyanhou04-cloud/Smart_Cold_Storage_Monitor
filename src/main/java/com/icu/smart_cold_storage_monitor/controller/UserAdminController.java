package com.icu.smart_cold_storage_monitor.controller;

import com.icu.smart_cold_storage_monitor.controller.dto.CreateUserRequest;
import com.icu.smart_cold_storage_monitor.controller.dto.CredentialResponse;
import com.icu.smart_cold_storage_monitor.controller.dto.ResetPasswordRequest;
import com.icu.smart_cold_storage_monitor.controller.dto.UserResponse;
import com.icu.smart_cold_storage_monitor.security.AppUserPrincipal;
import com.icu.smart_cold_storage_monitor.service.UserAccountService;
import com.icu.smart_cold_storage_monitor.service.UserAccountService.AccountCredentials;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account management, restricted to ADMIN by {@code SecurityConfig}; the per-target rules (no resetting another
 * administrator's password, no deactivating administrators) live in {@link UserAccountService}.
 */
@Tag(name = "User administration", description = "Administrator-only account management.")
@RestController
@RequestMapping("/api/users")
public class UserAdminController {

    private final UserAccountService accounts;

    public UserAdminController(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public List<UserResponse> list() {
        return accounts.listUsers().stream().map(UserResponse::from).toList();
    }

    /** Creates an OPERATOR or ADMIN account; 201 with the generated password if none was supplied. */
    @PostMapping
    @ApiResponse(responseCode = "201", description = "Account created; `generatedPassword` is returned only "
            + "in this response, and only when no password was supplied")
    public ResponseEntity<CredentialResponse> create(@Valid @RequestBody CreateUserRequest request,
                                                     @AuthenticationPrincipal AppUserPrincipal admin) {
        AccountCredentials created = accounts.createUser(request.username(), request.password(), request.role(),
                admin.getId());
        return ResponseEntity.created(URI.create("/api/users/" + created.user().id()))
                .body(CredentialResponse.from(created));
    }

    /** Sets a new password (generated if none was supplied) and ends the user's other sessions. */
    @PostMapping("/{id}/password-reset")
    public CredentialResponse resetPassword(@PathVariable long id,
                                            @Valid @RequestBody(required = false) ResetPasswordRequest request,
                                            @AuthenticationPrincipal AppUserPrincipal admin, HttpSession session) {
        String newPassword = request == null ? null : request.newPassword();
        return CredentialResponse.from(accounts.resetPassword(admin.getId(), id, newPassword, session.getId()));
    }

    /** Deactivates an operator account and ends its sessions; repeating the call is a no-op. */
    @PostMapping("/{id}/deactivation")
    public UserResponse deactivate(@PathVariable long id, @AuthenticationPrincipal AppUserPrincipal admin) {
        return UserResponse.from(accounts.deactivateUser(admin.getId(), id));
    }
}
