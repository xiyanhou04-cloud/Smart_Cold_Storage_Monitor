package com.icu.smart_cold_storage_monitor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icu.smart_cold_storage_monitor.model.Alert;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.ReadingMetric;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.repository.AlertRepository;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.service.DeviceSessionService;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.ReadingSubmission;
import com.icu.smart_cold_storage_monitor.service.UserAccountService;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Alert history and acknowledgment through the real security chain and a temporary SQLite database. */
@AutoConfigureMockMvc
class AlertApiTest extends SqliteIntegrationTest {

    static final String PASSWORD = "correct-horse-1";
    static final String MEAT = "ESP32-SIM-001";
    static final String FROZEN = "ESP32-SIM-005";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountService accounts;

    @Autowired
    DeviceSessionService deviceSessions;

    @Autowired
    ReadingIngestionService ingestion;

    @Autowired
    AlertRepository alerts;

    @Autowired
    AuditEventRepository auditEvents;

    MockHttpSession admin;
    MockHttpSession operator;
    /** Active MEAT temperature alert. */
    long meatTemperature;
    /** Resolved MEAT humidity alert, opened at the same time as {@link #meatTemperature}. */
    long meatHumidity;
    /** Active FROZEN temperature alert, the most recently opened. */
    long frozenTemperature;
    Instant frozenOpenedAt;

    @BeforeEach
    void setUp() throws Exception {
        accounts.createUser("admin1", PASSWORD, Role.ADMIN, null);
        accounts.createUser("operator1", PASSWORD, Role.OPERATOR, null);
        // RAW-MEAT 0 to 4 °C and 55 to 80 %RH; FROZEN -25 to -18 °C and 35 to 80 %RH.
        long meatSession = deviceSessions.ensureDeviceWithActiveSession(MEAT, "Raw meat", "RAW-MEAT", "RAW-MEAT")
                .id();
        long frozenSession = deviceSessions.ensureDeviceWithActiveSession(FROZEN, "Freezer", "FROZEN", "FROZEN").id();
        Instant base = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
        ingestion.ingest(MEAT, new ReadingSubmission(MEAT, meatSession, base, 5.0, 90.0));
        ingestion.ingest(MEAT, new ReadingSubmission(MEAT, meatSession, base.plusSeconds(1), 5.0, 70.0));
        frozenOpenedAt = base.plusSeconds(2);
        ingestion.ingest(FROZEN, new ReadingSubmission(FROZEN, frozenSession, frozenOpenedAt, -10.0, 50.0));

        for (Alert alert : alerts.findBySession(meatSession)) {
            if (alert.metric() == ReadingMetric.TEMPERATURE) {
                meatTemperature = alert.id();
            } else {
                meatHumidity = alert.id();
            }
        }
        frozenTemperature = alerts.findBySession(frozenSession).getFirst().id();
        admin = login("admin1");
        operator = login("operator1");
    }

    // ---- Access ------------------------------------------------------------------------------------------------

    @Test
    void everyEndpointNeedsASignedInUser() throws Exception {
        mvc.perform(get("/api/alerts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/alerts/{id}", meatTemperature)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/alerts/{id}/acknowledgment", meatTemperature).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acknowledgmentWithoutCsrfIsDeniedAndAudited() throws Exception {
        mvc.perform(post("/api/alerts/{id}/acknowledgment", meatTemperature).session(operator))
                .andExpect(status().isForbidden());

        assertThat(alerts.findById(meatTemperature).orElseThrow().acknowledged()).isFalse();
        assertThat(countAudit(AuditEventType.ACCESS_DENIED)).isEqualTo(1);
        assertThat(countAudit(AuditEventType.ALERT_ACKNOWLEDGED)).isZero();
    }

    // ---- Response format ---------------------------------------------------------------------------------------

    @Test
    void anActiveThresholdAlertInTheSharedFormat() throws Exception {
        mvc.perform(get("/api/alerts/{id}", meatTemperature).session(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(meatTemperature))
                .andExpect(jsonPath("$.deviceId").value(MEAT))
                .andExpect(jsonPath("$.sessionId").isNumber())
                .andExpect(jsonPath("$.kind").value("THRESHOLD"))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.source").value("THRESHOLD"))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.openedAt").isString())
                .andExpect(jsonPath("$.lastSeenAt").isString())
                .andExpect(jsonPath("$", hasKey("resolvedAt")))
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()))
                .andExpect(jsonPath("$.threshold.metric").value("TEMPERATURE"))
                .andExpect(jsonPath("$.threshold.direction").value("ABOVE"))
                .andExpect(jsonPath("$.threshold.unit").value("°C"))
                .andExpect(jsonPath("$.threshold.limit").value(4.0))
                .andExpect(jsonPath("$.threshold.openingValue").value(5.0))
                .andExpect(jsonPath("$.threshold.lastValue").value(5.0))
                .andExpect(jsonPath("$.threshold.peakValue").value(5.0))
                .andExpect(jsonPath("$.threshold.thresholdId").isNumber())
                .andExpect(jsonPath("$.risk").doesNotExist())
                .andExpect(jsonPath("$", hasKey("acknowledgment")))
                .andExpect(jsonPath("$.acknowledgment").value(nullValue()));
    }

    @Test
    void aResolvedHumidityAlert() throws Exception {
        mvc.perform(get("/api/alerts/{id}", meatHumidity).session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RESOLVED"))
                .andExpect(jsonPath("$.resolveReason").value("RECOVERED"))
                .andExpect(jsonPath("$.resolvedAt").isString())
                .andExpect(jsonPath("$.threshold.metric").value("HUMIDITY"))
                .andExpect(jsonPath("$.threshold.unit").value("%RH"))
                .andExpect(jsonPath("$.threshold.limit").value(80.0));
    }

    @Test
    void unknownAlertIs404() throws Exception {
        mvc.perform(get("/api/alerts/{id}", 999_999).session(operator)).andExpect(status().isNotFound());
        acknowledge(operator, 999_999, null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.requestId").isString());
    }

    // ---- Acknowledgment ----------------------------------------------------------------------------------------

    @Test
    void anOperatorAcknowledgesWithANoteAndTheAlertStaysActive() throws Exception {
        acknowledge(operator, meatTemperature, "{\"note\": \"Closed the door.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.alert.id").value(meatTemperature))
                .andExpect(jsonPath("$.alert.state").value("ACTIVE"))
                .andExpect(jsonPath("$.alert.acknowledgment.userId").isNumber())
                .andExpect(jsonPath("$.alert.acknowledgment.username").value("operator1"))
                .andExpect(jsonPath("$.alert.acknowledgment.at").isString())
                .andExpect(jsonPath("$.alert.acknowledgment.note").value("Closed the door."));

        mvc.perform(get("/api/alerts/{id}", meatTemperature).session(admin))
                .andExpect(jsonPath("$.acknowledgment.username").value("operator1"));
    }

    @Test
    void aRepeatedAcknowledgmentReturnsTheFirstOne() throws Exception {
        acknowledge(operator, meatTemperature, "{\"note\": \"First\"}").andExpect(status().isOk());

        acknowledge(admin, meatTemperature, "{\"note\": \"Second\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_ACKNOWLEDGED"))
                .andExpect(jsonPath("$.alert.acknowledgment.username").value("operator1"))
                .andExpect(jsonPath("$.alert.acknowledgment.note").value("First"));
        assertThat(countAudit(AuditEventType.ALERT_ACKNOWLEDGED)).isEqualTo(1);
    }

    @Test
    void anAdministratorAcknowledgesAResolvedAlertWithoutABody() throws Exception {
        acknowledge(admin, meatHumidity, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.alert.state").value("RESOLVED"))
                .andExpect(jsonPath("$.alert.acknowledgment.username").value("admin1"))
                .andExpect(jsonPath("$.alert.acknowledgment.note").value(nullValue()));
    }

    @Test
    void aNoteOver500CharactersIsRejected() throws Exception {
        acknowledge(operator, meatTemperature, "{\"note\": \"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("note"));
        assertThat(alerts.findById(meatTemperature).orElseThrow().acknowledged()).isFalse();
    }

    @Test
    void theAcknowledgmentShowsUpInAuditReviewForAdministratorsOnly() throws Exception {
        acknowledge(operator, meatTemperature, "{\"note\": \"Closed the door.\"}").andExpect(status().isOk());

        mvc.perform(get("/api/audit-events").param("eventType", "ALERT_ACKNOWLEDGED").param("actor", "operator1")
                        .session(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$.items[0].actorUsername").value("operator1"))
                .andExpect(jsonPath("$.items[0].targetType").value("ALERT"))
                .andExpect(jsonPath("$.items[0].targetId").value(String.valueOf(meatTemperature)))
                .andExpect(jsonPath("$.items[0].occurredAt").isString());
        mvc.perform(get("/api/audit-events").session(operator)).andExpect(status().isForbidden());
    }

    // ---- History -----------------------------------------------------------------------------------------------

    @Test
    void historyIsMostRecentlyOpenedFirst() throws Exception {
        mvc.perform(get("/api/alerts").session(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.items[0].id").value(frozenTemperature))
                .andExpect(jsonPath("$.items[1].id").value(meatHumidity))
                .andExpect(jsonPath("$.items[2].id").value(meatTemperature));
    }

    @Test
    void historyFilters() throws Exception {
        expectTotal(get("/api/alerts").param("state", "ACTIVE"), 2);
        expectTotal(get("/api/alerts").param("state", "RESOLVED"), 1);
        expectTotal(get("/api/alerts").param("deviceId", MEAT), 2);
        expectTotal(get("/api/alerts").param("deviceId", MEAT + "," + FROZEN), 3);
        expectTotal(get("/api/alerts").param("deviceId", MEAT).param("deviceId", FROZEN), 3);
        expectTotal(get("/api/alerts").param("deviceId", "NO-SUCH-DEVICE"), 0);
        expectTotal(get("/api/alerts").param("kind", "THRESHOLD"), 3);
        expectTotal(get("/api/alerts").param("kind", "RISK"), 0);
        expectTotal(get("/api/alerts").param("from", frozenOpenedAt.toString()), 1);
        expectTotal(get("/api/alerts").param("to", frozenOpenedAt.toString()), 2);
        expectTotal(get("/api/alerts").param("acknowledged", "false"), 3);

        acknowledge(operator, meatTemperature, null).andExpect(status().isOk());
        expectTotal(get("/api/alerts").param("acknowledged", "true"), 1);
        expectTotal(get("/api/alerts").param("acknowledged", "false").param("state", "ACTIVE"), 1);
    }

    @Test
    void historyPages() throws Exception {
        mvc.perform(get("/api/alerts").param("size", "1").param("page", "1").session(operator))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(meatHumidity))
                .andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/alerts").param("page", "-1").session(operator)).andExpect(status().isBadRequest());
    }

    @Test
    void invalidHistoryParametersAre400() throws Exception {
        String now = Instant.now().toString();
        mvc.perform(get("/api/alerts").param("from", now).param("to", now).session(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("to"));
        mvc.perform(get("/api/alerts").param("state", "OPEN").session(operator)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/alerts").param("kind", "SENSOR").session(operator)).andExpect(status().isBadRequest());
    }

    private void expectTotal(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                             int total) throws Exception {
        mvc.perform(request.session(operator)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(total));
    }

    private ResultActions acknowledge(MockHttpSession session, long alertId, String body) throws Exception {
        var request = post("/api/alerts/{id}/acknowledgment", alertId).session(session).with(csrf());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private long countAudit(AuditEventType type) {
        return auditEvents.findRecent(500).stream().filter(e -> e.eventType() == type).count();
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username).param("password", PASSWORD).session(session).with(csrf()))
                .andExpect(status().isOk());
        return session;
    }
}
