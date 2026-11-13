package com.icu.smart_cold_storage_monitor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Constraints of the sensor data tables (devices, storage_sessions, threshold_settings, readings). */
class SensorDataSchemaTest extends SqliteIntegrationTest {
    static final String T0 = "2026-11-12T08:00:00.000Z";
    static final String T1 = "2026-11-12T08:00:05.000Z";

    @Autowired
    DataSource dataSource;

    long session1;

    @BeforeEach
    void devicesAndSession() {
        device("ESP32-SIM-001");
        device("ESP32-SIM-002");
        session1 = session("ESP32-SIM-001", T0);
    }

    @Test
    void schemaCreatesSensorDataTables() {
        assertThat(jdbc.sql("SELECT name FROM sqlite_master WHERE type = 'table'").query(String.class).list())
            .contains("devices", "storage_sessions", "threshold_settings", "readings");
    }

    @Test
    void storesAReadingWithoutAThresholdVersion() {
        long id = reading("ESP32-SIM-001", session1, T1, 4.0032, 69.99565, null);

        assertThat(jdbc.sql("SELECT temperature_c FROM readings WHERE id = ?").param(id)
            .query(Double.class).single()).isEqualTo(4.0032);
    }

    @Test
    void storesAReadingWithItsSessionsThresholdVersion() {
        long threshold = threshold(session1, 0, 4);

        assertThatCode(() -> reading("ESP32-SIM-001", session1, T1, 4.0, 70, threshold)).doesNotThrowAnyException();
    }

    @Test
    void sameDeviceAndMeasuredAtIsUnique() {
        reading("ESP32-SIM-001", session1, T1, 4.0, 70, null);

        assertThatThrownBy(() -> reading("ESP32-SIM-001", session1, T1, 5.0, 70, null))
            .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void differentDevicesMayShareATimestamp() {
        long session2 = session("ESP32-SIM-002", T0);
        reading("ESP32-SIM-001", session1, T1, 4.0, 70, null);

        assertThatCode(() -> reading("ESP32-SIM-002", session2, T1, 1.2, 96, null)).doesNotThrowAnyException();
    }

    @Test
    void readingMustUseASessionOfTheSameDevice() {
        assertThatThrownBy(() -> reading("ESP32-SIM-002", session1, T1, 4.0, 70, null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void readingOfUnknownDeviceOrSessionIsRejected() {
        assertThatThrownBy(() -> reading("ESP32-SIM-999", session1, T1, 4.0, 70, null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> reading("ESP32-SIM-001", 999_999L, T1, 4.0, 70, null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void readingMustUseAThresholdVersionOfItsOwnSession() {
        long session2 = session("ESP32-SIM-002", T0);
        long otherThreshold = threshold(session2, 0, 2.2);

        assertThatThrownBy(() -> reading("ESP32-SIM-001", session1, T1, 4.0, 70, otherThreshold))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void humidityOutsideZeroToHundredIsRejected() {
        assertThatThrownBy(() -> reading("ESP32-SIM-001", session1, T1, 4.0, 100.01, null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> reading("ESP32-SIM-001", session1, T1, 4.0, -0.01, null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatCode(() -> reading("ESP32-SIM-001", session1, T1, 4.0, 100, null)).doesNotThrowAnyException();
    }

    @Test
    void infiniteTemperatureIsRejected() {
        assertThatThrownBy(() -> reading("ESP32-SIM-001", session1, T1, Double.POSITIVE_INFINITY, 70, null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nonCanonicalTimestampIsRejected() {
        // Same instant as T1 without milliseconds: allowing it would bypass UNIQUE (device_id, measured_at).
        assertThatThrownBy(() -> reading("ESP32-SIM-001", session1, "2026-11-12T08:00:05Z", 4.0, 70, null))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> reading("ESP32-SIM-001", session1, "2026-11-12 08:00:05.000Z", 4.0, 70, null))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deviceHasAtMostOneActiveSession() {
        assertThatThrownBy(() -> session("ESP32-SIM-001", T1)).isInstanceOf(DuplicateKeyException.class);

        jdbc.sql("UPDATE storage_sessions SET ended_at = ? WHERE id = ?").params(T1, session1).update();

        assertThatCode(() -> session("ESP32-SIM-001", T1)).doesNotThrowAnyException();
    }

    @Test
    void sessionCannotEndBeforeItStarts() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE storage_sessions SET ended_at = ? WHERE id = ?")
                .params("2026-11-12T07:59:59.999Z", session1).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void thresholdLowerMustBeBelowUpper() {
        assertThatThrownBy(() -> threshold(session1, 4, 4)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> threshold(session1, 8, 2)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deviceLocationIsOptionalButNotBlankOrTooLong() {
        assertThat(jdbc.sql("SELECT location_code IS NULL FROM devices WHERE id = 'ESP32-SIM-001'")
            .query(Boolean.class).single()).isTrue();
        assertThatCode(() -> jdbc.sql("UPDATE devices SET location_code = 'RAW-MEAT' WHERE id = 'ESP32-SIM-001'")
            .update()).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.sql("UPDATE devices SET location_code = '' WHERE id = 'ESP32-SIM-001'").update())
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE devices SET location_code = ? WHERE id = 'ESP32-SIM-001'")
            .param("X".repeat(65)).update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deviceWithReadingsCannotBeDeleted() {
        reading("ESP32-SIM-001", session1, T1, 4.0, 70, null);

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM devices WHERE id = 'ESP32-SIM-001'").update())
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rerunningSchemaOnStartupKeepsReadings() {
        reading("ESP32-SIM-001", session1, T1, 4.0, 70, null);

        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        assertThat(jdbc.sql("SELECT count(*) FROM readings").query(Long.class).single()).isEqualTo(1);
    }

    private void device(String id) {
        jdbc.sql("INSERT INTO devices (id, name) VALUES (?, ?)").params(id, "Simulated " + id).update();
    }

    private long session(String device, String startedAt) {
        return jdbc.sql("""
                INSERT INTO storage_sessions (device_id, product_type, started_at) VALUES (?, 'RAW-MEAT', ?)
                RETURNING id
                """).params(device, startedAt).query(Long.class).single();
    }

    private long threshold(long session, double lower, double upper) {
        return jdbc.sql("""
                INSERT INTO threshold_settings (session_id, lower_c, upper_c, effective_from) VALUES (?, ?, ?, ?)
                RETURNING id
                """).params(session, lower, upper, T0).query(Long.class).single();
    }

    private long reading(String device, long session, String measuredAt, double temperature, double humidity,
                         Long threshold) {
        return jdbc.sql("""
                INSERT INTO readings (device_id, session_id, measured_at, received_at, temperature_c, humidity_pct,
                                      threshold_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """).params(device, session, measuredAt, T1, temperature, humidity, threshold)
            .query(Long.class).single();
    }
}
