package com.icu.smart_cold_storage_monitor.setup;

import com.icu.smart_cold_storage_monitor.service.InitialAdminService;
import com.icu.smart_cold_storage_monitor.service.UserAccountService.AccountCredentials;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * First-run setup, executed on every startup after the schema has been applied.
 *
 * <p>The database file and tables are created by {@code DatabaseConfig} and {@code schema.sql} (idempotent). This
 * runner then creates an administrator only if none exists yet; on later startups it does nothing.
 *
 * <p>Credentials come from {@code app.bootstrap.admin.username} / {@code app.bootstrap.admin.password}
 * (environment variables {@code APP_BOOTSTRAP_ADMIN_USERNAME} / {@code APP_BOOTSTRAP_ADMIN_PASSWORD}). Without a
 * configured password a random one is generated and printed to the console once. A configured password is never
 * logged.
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final InitialAdminService initialAdmin;
    private final String username;
    private final String password;

    public AdminBootstrapRunner(InitialAdminService initialAdmin,
                                @Value("${app.bootstrap.admin.username:}") String username,
                                @Value("${app.bootstrap.admin.password:}") String password) {
        this.initialAdmin = initialAdmin;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        Optional<AccountCredentials> created = initialAdmin.ensureAdminExists(username, password);
        if (created.isEmpty()) {
            log.info("Administrator account already exists; skipping initial setup.");
            return;
        }
        AccountCredentials admin = created.get();
        if (admin.passwordGenerated()) {
            log.warn("""

                    ================================================================
                     Initial administrator created (first run).
                       username: {}
                       password: {}
                     This password is shown only once. Store it now.
                    ================================================================""",
                    admin.user().username(), admin.generatedPassword());
        } else {
            log.info("Initial administrator '{}' created with the configured password.", admin.user().username());
        }
    }
}
