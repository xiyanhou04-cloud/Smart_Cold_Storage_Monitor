package com.icu.smart_cold_storage_monitor.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.User;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.repository.UserRepository;
import com.icu.smart_cold_storage_monitor.service.InitialAdminService;
import com.icu.smart_cold_storage_monitor.service.UserAccountService.AccountCredentials;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/** First-run administrator creation against a temporary SQLite database. */
class AdminBootstrapRunnerTest extends SqliteIntegrationTest {

    @Autowired
    AdminBootstrapRunner runner;

    @Autowired
    InitialAdminService initialAdmin;

    @Autowired
    UserRepository users;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void firstRunWithoutConfigurationCreatesDefaultAdminWithGeneratedPassword() {
        AccountCredentials created = initialAdmin.ensureAdminExists(null, null).orElseThrow();

        assertThat(created.passwordGenerated()).isTrue();
        assertThat(created.generatedPassword()).hasSizeGreaterThanOrEqualTo(20);
        User admin = users.findByUsername(InitialAdminService.DEFAULT_USERNAME).orElseThrow();
        assertThat(admin.role()).isEqualTo(Role.ADMIN);
        assertThat(admin.enabled()).isTrue();
        assertThat(admin.passwordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches(created.generatedPassword(), admin.passwordHash())).isTrue();

        assertThat(auditEvents.findRecent(10))
                .filteredOn(e -> e.eventType() == AuditEventType.USER_CREATED)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.actorId()).isNull();
                    assertThat(e.targetId()).isEqualTo(String.valueOf(admin.id()));
                })
                .extracting(AuditEvent::details).asString().doesNotContain(created.generatedPassword());
    }

    @Test
    void configuredCredentialsAreUsedAndPasswordIsNotEchoed() {
        AccountCredentials created = initialAdmin.ensureAdminExists("setup-admin", "bootstrap-pass-1").orElseThrow();

        assertThat(created.passwordGenerated()).isFalse();
        assertThat(created.generatedPassword()).isNull();
        User admin = users.findByUsername("setup-admin").orElseThrow();
        assertThat(passwordEncoder.matches("bootstrap-pass-1", admin.passwordHash())).isTrue();
    }

    @Test
    void configuredUsernameWithoutPasswordGetsGeneratedPassword() {
        AccountCredentials created = initialAdmin.ensureAdminExists("ops-admin", "").orElseThrow();

        assertThat(created.user().username()).isEqualTo("ops-admin");
        assertThat(created.passwordGenerated()).isTrue();
    }

    @Test
    void secondRunSkipsWhenAdministratorExists() {
        AccountCredentials first = initialAdmin.ensureAdminExists(null, null).orElseThrow();
        String originalHash = users.findById(first.user().id()).orElseThrow().passwordHash();

        Optional<AccountCredentials> second = initialAdmin.ensureAdminExists(null, null);
        runner.run(null);

        assertThat(second).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM users").query(Integer.class).single()).isEqualTo(1);
        assertThat(users.findById(first.user().id()).orElseThrow().passwordHash()).isEqualTo(originalHash);
    }

    @Test
    void existingAdministratorWithAnotherNameAlsoSkipsSetup() {
        users.insert("chief", passwordEncoder.encode("chief-pass-1"), Role.ADMIN, true, Instant.now());

        assertThat(initialAdmin.ensureAdminExists("setup-admin", "bootstrap-pass-1")).isEmpty();
        assertThat(users.existsByUsername("setup-admin")).isFalse();
    }

    @Test
    void operatorsOnlyStillGetAnAdministrator() {
        users.insert("op1", passwordEncoder.encode("op-pass-123"), Role.OPERATOR, true, Instant.now());

        assertThat(initialAdmin.ensureAdminExists(null, null)).isPresent();
        assertThat(users.existsByRole(Role.ADMIN)).isTrue();
    }

    @Test
    void runnerCreatesAdministratorOnEmptyDatabase() {
        runner.run(null);

        assertThat(users.findByUsername(InitialAdminService.DEFAULT_USERNAME)).isPresent();
    }
}
