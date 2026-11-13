package com.icu.smart_cold_storage_monitor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.security.DeviceCredentialService;
import com.icu.smart_cold_storage_monitor.service.DeviceSessionService;
import com.icu.smart_cold_storage_monitor.service.UserAccountService;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** {@code POST /api/readings} through the device security chain and a temporary SQLite database. */
@AutoConfigureMockMvc
class ReadingIngestionApiTest extends SqliteIntegrationTest {

    static final String DEVICE = "ESP32-SIM-001";
    static final String OTHER_DEVICE = "ESP32-SIM-002";

    @Autowired
    MockMvc mvc;

    @Autowired
    DeviceSessionService deviceSessions;

    @Autowired
    DeviceCredentialService credentials;

    @Autowired
    UserAccountService accounts;

    @Autowired
    AuditEventRepository auditEvents;

    long session;
    long otherSession;
    Instant base;

    @BeforeEach
    void deviceWithSession() {
        session = deviceSessions.ensureDeviceWithActiveSession(DEVICE, "Raw meat sensor", "RAW-MEAT", "RAW-MEAT")
                .id();
        otherSession = deviceSessions.ensureDeviceWithActiveSession(OTHER_DEVICE, "Veg sensor", "VEG-COLD", "VEG-COLD")
                .id();
        // Sessions start "now"; readings use later timestamps well inside the 60 s future limit.
        base = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
    }

    // ---- Accepted readings -------------------------------------------------------------------------------------

    @Test
    void newReadingIsStoredAndReturnedWith201() throws Exception {
        submit(DEVICE, body(DEVICE, session, base, 4.0032, 69.99565))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.deviceId").value(DEVICE))
                .andExpect(jsonPath("$.sessionId").value(session))
                .andExpect(jsonPath("$.temperatureC").value(4.0032))
                .andExpect(jsonPath("$.humidityPct").value(69.99565))
                .andExpect(jsonPath("$.receivedAt").isString())
                .andExpect(jsonPath("$.thresholdId").isNumber());

        assertThat(jdbc.sql("SELECT measured_at FROM readings WHERE device_id = ?").param(DEVICE)
                .query(String.class).single()).isEqualTo(canonical(base));
        // The reading records the session's current threshold version (the location default here).
        assertThat(jdbc.sql("SELECT threshold_id FROM readings WHERE device_id = ?").param(DEVICE)
                .query(Long.class).single())
                .isEqualTo(jdbc.sql("SELECT max(id) FROM threshold_settings WHERE session_id = ?").param(session)
                        .query(Long.class).single());
    }

    @Test
    void identicalRetryReturns200WithTheStoredReading() throws Exception {
        String body = body(DEVICE, session, base, 4.0, 70.0);
        Number id = com.jayway.jsonpath.JsonPath.read(
                submit(DEVICE, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
                "$.id");

        submit(DEVICE, body).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.longValue()));

        assertThat(readingCount()).isEqualTo(1);
    }

    @Test
    void sameTimestampWithDifferentValuesIs409AndKeepsTheOriginal() throws Exception {
        submit(DEVICE, body(DEVICE, session, base, 4.0, 70.0)).andExpect(status().isCreated());

        submit(DEVICE, body(DEVICE, session, base, 9.0, 70.0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_CONFLICT"))
                .andExpect(jsonPath("$.requestId").isString());

        assertThat(jdbc.sql("SELECT temperature_c FROM readings").query(Double.class).single()).isEqualTo(4.0);
    }

    @Test
    void olderOrEqualTimestampThanTheLatestIsOutOfOrder() throws Exception {
        submit(DEVICE, body(DEVICE, session, base.plusSeconds(10), 4.0, 70.0)).andExpect(status().isCreated());

        submit(DEVICE, body(DEVICE, session, base.plusSeconds(5), 4.0, 70.0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OUT_OF_ORDER"));
        assertThat(readingCount()).isEqualTo(1);
    }

    @Test
    void devicesAreOrderedIndependently() throws Exception {
        submit(DEVICE, body(DEVICE, session, base.plusSeconds(10), 4.0, 70.0)).andExpect(status().isCreated());

        submit(OTHER_DEVICE, body(OTHER_DEVICE, otherSession, base.plusSeconds(5), 1.2, 96.0))
                .andExpect(status().isCreated());
    }

    @Test
    void timestampUpToSixtySecondsAheadIsAccepted() throws Exception {
        submit(DEVICE, body(DEVICE, session, Instant.now().plusSeconds(55), 4.0, 70.0))
                .andExpect(status().isCreated());
    }

    @Test
    void boundaryValuesAreAccepted() throws Exception {
        submit(DEVICE, body(DEVICE, session, base, -40, 0)).andExpect(status().isCreated());
        submit(DEVICE, body(DEVICE, session, base.plusSeconds(5), 125, 100)).andExpect(status().isCreated());
    }

    // ---- Invalid input: 400, nothing stored ----------------------------------------------------------------------

    @Test
    void humidityOutsideZeroToHundredIs400() throws Exception {
        submit(DEVICE, body(DEVICE, session, base, 4.0, 100.5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("humidityPct"));
        submit(DEVICE, body(DEVICE, session, base, 4.0, -1)).andExpect(status().isBadRequest());
        assertThat(readingCount()).isZero();
    }

    @Test
    void implausibleTemperatureIs400() throws Exception {
        submit(DEVICE, body(DEVICE, session, base, 200, 70))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("temperatureC"));
    }

    @Test
    void nonFiniteNumberIsMalformed() throws Exception {
        String body = """
                {"deviceId":"%s","sessionId":%d,"timestamp":"%s","temperatureC":NaN,"humidityPct":70}
                """.formatted(DEVICE, session, base);

        submit(DEVICE, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(readingCount()).isZero();
    }

    @Test
    void missingFieldsAre400() throws Exception {
        submit(DEVICE, "{\"deviceId\":\"" + DEVICE + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(4));
    }

    @Test
    void unparseableTimestampIsMalformed() throws Exception {
        submit(DEVICE, """
                {"deviceId":"%s","sessionId":%d,"timestamp":"yesterday","temperatureC":4,"humidityPct":70}
                """.formatted(DEVICE, session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void timestampMoreThanSixtySecondsAheadIs400() throws Exception {
        submit(DEVICE, body(DEVICE, session, Instant.now().plusSeconds(90), 4.0, 70.0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("timestamp"));
        assertThat(readingCount()).isZero();
    }

    @Test
    void timestampBeforeTheSessionStartedIs400() throws Exception {
        submit(DEVICE, body(DEVICE, session, base.minusSeconds(3600), 4.0, 70.0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("timestamp"));
    }

    // ---- Sessions ----------------------------------------------------------------------------------------------

    @Test
    void unknownSessionIs404() throws Exception {
        submit(DEVICE, body(DEVICE, 999_999, base, 4.0, 70.0))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anotherDevicesSessionIs404() throws Exception {
        submit(DEVICE, body(DEVICE, otherSession, base, 4.0, 70.0)).andExpect(status().isNotFound());
        assertThat(readingCount()).isZero();
    }

    @Test
    void endedSessionIs409() throws Exception {
        endSession(session);

        submit(DEVICE, body(DEVICE, session, base, 4.0, 70.0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_ACTIVE"));
    }

    @Test
    void identicalRetryStillSucceedsAfterTheSessionEnded() throws Exception {
        String body = body(DEVICE, session, base, 4.0, 70.0);
        submit(DEVICE, body).andExpect(status().isCreated());
        endSession(session);

        submit(DEVICE, body).andExpect(status().isOk());
    }

    // ---- Credentials -------------------------------------------------------------------------------------------

    @Test
    void missingCredentialIs401() throws Exception {
        mvc.perform(post("/api/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(body(DEVICE, session, base, 4.0, 70.0)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_DEVICE_CREDENTIAL"))
                .andExpect(header().exists("X-Request-Id"));
        assertThat(readingCount()).isZero();
    }

    @Test
    void wrongTokenIs401() throws Exception {
        String header = credentials.authorizationHeader(DEVICE);
        String tampered = header.substring(0, header.length() - 1) + (header.endsWith("0") ? "1" : "0");

        mvc.perform(request(tampered, body(DEVICE, session, base, 4.0, 70.0)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_DEVICE_CREDENTIAL"));
    }

    @Test
    void tokenOfAnotherDeviceIs401() throws Exception {
        String otherToken = credentials.authorizationHeader(OTHER_DEVICE).substring(("Device " + OTHER_DEVICE).length());

        mvc.perform(request("Device " + DEVICE + otherToken, body(DEVICE, session, base, 4.0, 70.0)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenForAnUnregisteredDeviceIs401() throws Exception {
        submit("ESP32-SIM-999", body("ESP32-SIM-999", session, base, 4.0, 70.0))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledDeviceIs401() throws Exception {
        jdbc.sql("UPDATE devices SET enabled = 0 WHERE id = ?").param(DEVICE).update();

        submit(DEVICE, body(DEVICE, session, base, 4.0, 70.0)).andExpect(status().isUnauthorized());
    }

    @Test
    void bodyDeviceIdMustMatchTheCredentialAndTheDenialIsAudited() throws Exception {
        submit(OTHER_DEVICE, body(DEVICE, session, base, 4.0, 70.0))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(readingCount()).isZero();
        assertThat(auditEvents.findRecent(10)).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo(AuditEventType.ACCESS_DENIED);
            assertThat(e.targetId()).isEqualTo("POST /api/readings");
        });
    }

    @Test
    void browserSessionCannotSubmitReadings() throws Exception {
        accounts.createUser("admin1", "correct-horse-1", Role.ADMIN, null);
        MockHttpSession browser = new MockHttpSession();
        mvc.perform(post("/api/auth/login").param("username", "admin1").param("password", "correct-horse-1")
                .session(browser).with(csrf())).andExpect(status().isOk());

        mvc.perform(post("/api/readings").session(browser).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(DEVICE, session, base, 4.0, 70.0)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_DEVICE_CREDENTIAL"));
        assertThat(readingCount()).isZero();
    }

    @Test
    void noCsrfTokenIsNeededAndNoSessionIsCreated() throws Exception {
        submit(DEVICE, body(DEVICE, session, base, 4.0, 70.0))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------

    private ResultActions submit(String credentialDevice, String body) throws Exception {
        return mvc.perform(request(credentials.authorizationHeader(credentialDevice), body));
    }

    private static MockHttpServletRequestBuilder request(String authorization, String body) {
        return post("/api/readings")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String body(String device, long session, Instant timestamp, double temperature, double humidity) {
        return String.format(Locale.ROOT,
                "{\"deviceId\":\"%s\",\"sessionId\":%d,\"timestamp\":\"%s\",\"temperatureC\":%s,\"humidityPct\":%s}",
                device, session, timestamp, temperature, humidity);
    }

    private static String canonical(Instant instant) {
        return com.icu.smart_cold_storage_monitor.repository.SqlTimestamps.format(instant);
    }

    private long readingCount() {
        return jdbc.sql("SELECT count(*) FROM readings").query(Long.class).single();
    }

    private void endSession(long id) {
        jdbc.sql("UPDATE storage_sessions SET ended_at = started_at WHERE id = ?").param(id).update();
    }
}
