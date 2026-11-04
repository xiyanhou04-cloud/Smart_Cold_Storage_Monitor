package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.repository.UserRepository;
import com.icu.smart_cold_storage_monitor.service.UserAccountService.AccountCredentials;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Ensures the system has an administrator so a fresh installation can be signed into.
 *
 * <p>If any ADMIN account exists, nothing happens. Otherwise one administrator is created through
 * {@link UserAccountService#createUser} (BCrypt hash plus a USER_CREATED audit event in one transaction), using the
 * configured username (default {@code admin}) and the configured password, or a random password when none is set.
 */
@Service
public class InitialAdminService {

    public static final String DEFAULT_USERNAME = "admin";

    private final UserRepository users;
    private final UserAccountService accounts;

    public InitialAdminService(UserRepository users, UserAccountService accounts) {
        this.users = users;
        this.accounts = accounts;
    }

    /**
     * Creates the initial administrator if no administrator exists.
     *
     * @param configuredUsername username to use, or {@code null}/blank for {@value #DEFAULT_USERNAME}
     * @param configuredPassword password to use, or {@code null}/empty to generate a random one
     * @return the created account, or empty if an administrator already existed
     */
    public Optional<AccountCredentials> ensureAdminExists(String configuredUsername, String configuredPassword) {
        if (users.existsByRole(Role.ADMIN)) {
            return Optional.empty();
        }
        String username = configuredUsername == null || configuredUsername.isBlank()
                ? DEFAULT_USERNAME
                : configuredUsername.strip();
        return Optional.of(accounts.createUser(username, configuredPassword, Role.ADMIN, null));
    }
}
