package com.icu.smart_cold_storage_monitor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.repository.SqlTimestamps;
import com.icu.smart_cold_storage_monitor.service.DeviceSessionService;
import com.icu.smart_cold_storage_monitor.service.UserAccountService;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /api/readings/query} through the browser security chain and a temporary SQLite database. */
@AutoConfigureMockMvc
class ReadingQueryApiTest extends SqliteIntegrationTest {

    static final String PASSWORD = "correct-horse-1";
    static final String A = "ESP32-SIM-001";
    static final String B = "ESP32-SIM-002";
    static final String C = "ESP32-SIM-003";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountService accounts;

    @Autowired
    DeviceSessionService deviceSessions;

    MockHttpSession operator;
    Instant now;

    @BeforeEach
    void devicesReadingsAndOperator() throws Exception {
        accounts.createUser("operator1", PASSWORD, Role.OPERATOR, null);
        accounts.createUser("admin1", PASSWORD, Role.ADMIN, null);
        operator = login("operator1");
        // Registered out of id order to check that the response is ordered by device id.
        long sessionC = deviceSessions.ensureDeviceWithActiveSession(C, "Sensor C", "VEG-COOL").id();
        long sessionA = deviceSessions.ensureDeviceWithActiveSession(A, "Sensor A", "RAW-MEAT").id();
        long sessionB = deviceSessions.ensureDeviceWithActiveSession(B, "Sensor B", "VEG-COLD").id();
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        reading(A, sessionA, ago(10), 3.0, 60.0);   // outside the default 5 minutes
        reading(A, sessionA, ago(4), 4.0, 70.0);
        reading(A, sessionA, ago(1), 5.0, 71.0);
        reading(B, sessionB, ago(2), 1.2, 96.0);
        // C has no readings.
        assertThat(sessionC).isPositive();
    }

    // ---- Access ------------------------------------------------------------------------------------------------

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/readings/query"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void operatorAndAdminMayQuery() throws Exception {
        mvc.perform(get("/api/readings/query").session(operator)).andExpect(status().isOk());
        mvc.perform(get("/api/readings/query").session(login("admin1"))).andExpect(status().isOk());
    }

    // ---- Defaults ----------------------------------------------------------------------------------------------

    @Test
    void defaultsToEveryDeviceBothMetricsAndTheLastFiveMinutes() throws Exception {
        JsonNode body = json(query());

        assertThat(body.propertyNames()).containsExactly(A, B, C);
        assertThat(values(body.get(A).get("temperatureC"))).containsExactly(4.0, 5.0);
        assertThat(values(body.get(A).get("humidityPct"))).containsExactly(70.0, 71.0);
        assertThat(body.get(A).get("temperatureC").get(0).get("timestamp").asString())
                .isEqualTo(ago(4).toString());
        assertThat(values(body.get(B).get("temperatureC"))).containsExactly(1.2);
        assertThat(body.get(B).get("humidityPct")).hasSize(1);
        assertThat(body.get(C).propertyNames()).containsExactlyInAnyOrder("temperatureC", "humidityPct");
        assertThat(body.get(C).get("temperatureC")).isEmpty();
        assertThat(body.get(C).get("humidityPct")).isEmpty();
    }

    @Test
    void noRegisteredDevicesGivesAnEmptyObject() throws Exception {
        jdbc.sql("DELETE FROM readings").update();
        jdbc.sql("DELETE FROM storage_sessions").update();
        jdbc.sql("DELETE FROM devices").update();

        assertThat(json(query()).propertyNames()).isEmpty();
    }

    // ---- Device filter -----------------------------------------------------------------------------------------

    @Test
    void filtersOneOrSeveralDevicesByRepeatOrComma() throws Exception {
        assertThat(json(query().param("deviceId", B)).propertyNames()).containsExactly(B);
        assertThat(json(query().param("deviceId", C, A)).propertyNames()).containsExactly(A, C);
        assertThat(json(query().param("deviceId", C + "," + A)).propertyNames()).containsExactly(A, C);
    }

    @Test
    void unknownDeviceIsRejected() throws Exception {
        mvc.perform(query().param("deviceId", A + ",ESP32-SIM-999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("deviceId"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Unknown device id(s): ESP32-SIM-999."));
    }

    @Test
    void tooManyDevicesAreRejected() throws Exception {
        String[] ids = IntStream.range(0, 51).mapToObj(i -> "D" + i).toArray(String[]::new);

        mvc.perform(query().param("deviceId", ids))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("deviceId"));
    }

    // ---- Metric filter -----------------------------------------------------------------------------------------

    @Test
    void temperatureOnlyOmitsHumidity() throws Exception {
        JsonNode body = json(query().param("metric", "temperature"));

        assertThat(body.get(A).propertyNames()).containsExactly("temperatureC");
        assertThat(body.get(C).propertyNames()).containsExactly("temperatureC");
    }

    @Test
    void humidityOnlyOmitsTemperature() throws Exception {
        assertThat(json(query().param("metric", "HUMIDITY")).get(A).propertyNames()).containsExactly("humidityPct");
    }

    @Test
    void bothMetricsMayBeNamed() throws Exception {
        assertThat(json(query().param("metric", "temperature,humidity")).get(A).propertyNames())
                .containsExactlyInAnyOrder("temperatureC", "humidityPct");
    }

    @Test
    void unknownMetricIsRejected() throws Exception {
        mvc.perform(query().param("metric", "pressure"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("metric"));
    }

    // ---- Time range --------------------------------------------------------------------------------------------

    @Test
    void windowWidensTheDefaultRange() throws Exception {
        assertThat(values(json(query().param("window", "PT15M")).get(A).get("temperatureC")))
                .containsExactly(3.0, 4.0, 5.0);
        assertThat(values(json(query().param("window", "15m")).get(A).get("temperatureC")))
                .containsExactly(3.0, 4.0, 5.0);
        assertThat(values(json(query().param("window", "90s")).get(A).get("temperatureC")))
                .containsExactly(5.0);
    }

    @Test
    void explicitRangeIncludesFromAndExcludesTo() throws Exception {
        JsonNode body = json(query().param("from", ago(10).toString()).param("to", ago(1).toString()));

        assertThat(values(body.get(A).get("temperatureC"))).containsExactly(3.0, 4.0);
        assertThat(values(body.get(B).get("temperatureC"))).containsExactly(1.2);
    }

    @Test
    void fromAloneRunsToNowAndToAloneLooksBackFiveMinutes() throws Exception {
        assertThat(values(json(query().param("from", ago(4).toString())).get(A).get("temperatureC")))
                .containsExactly(4.0, 5.0);
        assertThat(values(json(query().param("to", ago(3).toString())).get(A).get("temperatureC")))
                .containsExactly(4.0);
    }

    @Test
    void invalidRangesAreRejected() throws Exception {
        expectFieldError(query().param("from", ago(1).toString()).param("to", ago(2).toString()), "to");
        expectFieldError(query().param("from", ago(1).toString()).param("to", ago(1).toString()), "to");
        expectFieldError(query().param("from", now.minus(Duration.ofHours(7)).toString()), "from");
        expectFieldError(query().param("from", ago(10).toString()).param("window", "5m"), "window");
        expectFieldError(query().param("window", "0s"), "window");
        expectFieldError(query().param("window", "PT7H"), "window");
        expectFieldError(query().param("window", "soon"), "window");
        expectFieldError(query().param("from", "yesterday"), "from");
    }

    @Test
    void sixHoursIsTheLongestAllowedRange() throws Exception {
        mvc.perform(query().param("window", "6h")).andExpect(status().isOk());
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder query() {
        return get("/api/readings/query").session(operator);
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonMapper.builder().build().readTree(body);
    }

    private void expectFieldError(MockHttpServletRequestBuilder request, String field) throws Exception {
        ResultActions result = mvc.perform(request);
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
    }

    private static List<Double> values(JsonNode points) {
        return StreamSupport.stream(points.spliterator(), false).map(p -> p.get("value").asDouble()).toList();
    }

    private Instant ago(int minutes) {
        return now.minus(Duration.ofMinutes(minutes));
    }

    private void reading(String device, long session, Instant measuredAt, double temperature, double humidity) {
        jdbc.sql("""
                INSERT INTO readings (device_id, session_id, measured_at, received_at, temperature_c, humidity_pct)
                VALUES (?, ?, ?, ?, ?, ?)""")
                .params(device, session, SqlTimestamps.format(measuredAt), SqlTimestamps.format(measuredAt),
                        temperature, humidity)
                .update();
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").param("username", username).param("password", PASSWORD)
                .session(session).with(csrf())).andExpect(status().isOk());
        return session;
    }
}
