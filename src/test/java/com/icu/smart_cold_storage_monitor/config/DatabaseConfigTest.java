package com.icu.smart_cold_storage_monitor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.repository.UserRepository;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class DatabaseConfigTest extends SqliteIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    UserRepository users;

    @Autowired
    AuditEventRepository auditEvents;

    @Test
    void everyConnectionEnforcesForeignKeysAndBusyTimeout() {
        // Several queries so more than one pooled connection is likely exercised.
        for (int i = 0; i < 5; i++) {
            assertThat(jdbc.sql("PRAGMA foreign_keys").query(Integer.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("PRAGMA busy_timeout").query(Integer.class).single()).isEqualTo(5000);
        }
        assertThat(jdbc.sql("PRAGMA journal_mode").query(String.class).single()).isEqualToIgnoringCase("wal");
    }

    @Test
    void schemaCreatesUsersAndAuditTables() {
        assertThat(jdbc.sql("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
                .query(String.class).list())
                .contains("users", "audit_events");
    }

    @Test
    void rerunningSchemaOnStartupKeepsExistingData() {
        users.insert("keeper", "hash", Role.OPERATOR, true, Instant.parse("2026-11-04T10:00:00Z"));

        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        assertThat(users.findByUsername("keeper")).isPresent();
    }

    @Test
    void auditEventWithUnknownActorIsRejectedByForeignKey() {
        AuditEvent orphan = new AuditEvent(null, 999_999L, null, AuditEventType.LOGOUT, Instant.now(),
                AuditResult.SUCCESS, "USER", "999999", null);

        assertThatThrownBy(() -> auditEvents.insert(orphan)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateUsernameIsRejectedByUniqueConstraint() {
        users.insert("dup_user", "hash", Role.OPERATOR, true, Instant.now());

        assertThatThrownBy(() -> users.insert("dup_user", "hash2", Role.ADMIN, true, Instant.now()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void checkConstraintViolationIsTranslated() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO users (username, password_hash, role, enabled, created_at)
                        VALUES ('bad_role', 'hash', 'SUPERUSER', 1, '2026-11-04T10:00:00.000Z')
                        """).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void timestampsRoundTripAndSortChronologically() {
        Instant whole = Instant.parse("2026-11-04T10:00:00Z");
        Instant fraction = Instant.parse("2026-11-04T10:00:00.500Z");
        auditEvents.insert(new AuditEvent(null, null, "a", AuditEventType.LOGIN_FAILURE, whole,
                AuditResult.FAILURE, "USER", null, null));
        auditEvents.insert(new AuditEvent(null, null, "b", AuditEventType.LOGIN_FAILURE, fraction,
                AuditResult.FAILURE, "USER", null, null));

        assertThat(auditEvents.findRecent(10))
                .extracting(AuditEvent::occurredAt)
                .containsExactly(fraction, whole);
    }
}
