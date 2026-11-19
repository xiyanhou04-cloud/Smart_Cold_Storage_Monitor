package com.icu.smart_cold_storage_monitor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A transaction whose {@code BEGIN IMMEDIATE} times out must not leave its connection in the pool half switched to
 * manual commit, or the next transaction on it would commit statement by statement. One pooled connection makes
 * sure the next transaction would get that same connection.
 */
@TestPropertySource(properties = {"app.database.busy-timeout-ms=100", "app.database.max-pool-size=1"})
class SqliteLockContentionTest extends SqliteIntegrationTest {

    @Autowired PlatformTransactionManager transactionManager;
    @Value("${app.database.path}") String path;

    @Test
    void aTransactionThatCouldNotBeginLeavesTheNextTransactionAtomic() throws Exception {
        var transaction = new TransactionTemplate(transactionManager);
        try (var lock = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = lock.createStatement()) {
            sql.execute("BEGIN IMMEDIATE");
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> insertDevice("blocked")))
                    .isInstanceOf(CannotCreateTransactionException.class);
            sql.execute("COMMIT");
        }

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insertDevice("rolled-back");
            throw new IllegalStateException("roll back");
        })).isInstanceOf(IllegalStateException.class);
        transaction.executeWithoutResult(status -> insertDevice("committed"));

        assertThat(jdbc.sql("SELECT id FROM devices ORDER BY id").query(String.class).list())
                .containsExactly("committed");
    }

    private void insertDevice(String id) {
        jdbc.sql("INSERT INTO devices (id, name) VALUES (?, 'test')").param(id).update();
    }
}
