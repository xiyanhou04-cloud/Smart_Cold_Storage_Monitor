package com.icu.smart_cold_storage_monitor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.service.DeviceSessionService;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.ReadingSubmission;
import com.icu.smart_cold_storage_monitor.service.UserAccountService;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import com.jayway.jsonpath.JsonPath;
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

/** Threshold settings and change requests through the real security chain and a temporary SQLite database. */
@AutoConfigureMockMvc
class ThresholdApiTest extends SqliteIntegrationTest {

    static final String PASSWORD = "correct-horse-1";
    static final String MEAT = "ESP32-SIM-001";
    static final String FROZEN = "ESP32-SIM-005";
    /** RAW-MEAT default from application.yaml. */
    static final String MEAT_DEFAULT = limits(0, 4, 55, 80);
    static final String CUSTOM = limits(1, 5, 60, 85);

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountService accounts;

    @Autowired
    DeviceSessionService deviceSessions;

    @Autowired
    ReadingIngestionService ingestion;

    @Autowired
    AuditEventRepository auditEvents;

    long meatSession;
    MockHttpSession admin;
    MockHttpSession operator;
    MockHttpSession otherOperator;

    @BeforeEach
    void setUp() throws Exception {
        accounts.createUser("admin1", PASSWORD, Role.ADMIN, null);
        accounts.createUser("operator1", PASSWORD, Role.OPERATOR, null);
        accounts.createUser("operator2", PASSWORD, Role.OPERATOR, null);
        meatSession = deviceSessions.ensureDeviceWithActiveSession(MEAT, "Raw meat", "RAW-MEAT", "RAW-MEAT").id();
        deviceSessions.ensureDeviceWithActiveSession(FROZEN, "Freezer", "FROZEN", "FROZEN");
        admin = login("admin1");
        operator = login("operator1");
        otherOperator = login("operator2");
    }

    // ---- Access control and validation -------------------------------------------------------------------------

    @Test
    void everyEndpointNeedsASignedInUser() throws Exception {
        mvc.perform(get("/api/settings/thresholds")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/settings/threshold-requests")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/settings/thresholds/{id}", MEAT).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(CUSTOM)).andExpect(status().isUnauthorized());
    }

    @Test
    void changesNeedACsrfToken() throws Exception {
        mvc.perform(put("/api/settings/thresholds/{id}", MEAT).session(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(CUSTOM))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void invalidLimitsAreRejectedWithAFieldError() throws Exception {
        submit(admin, MEAT, limits(4, 4, 55, 80)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("temperatureUpperC"));
        submit(admin, MEAT, limits(0, 4, 90, 50)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("humidityUpperPct"));
        submit(admin, MEAT, limits(-41, 4, 55, 80)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("temperatureLowerC"));
        submit(operator, MEAT, limits(0, 4, 55, 100.5)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("humidityUpperPct"));
        submit(admin, MEAT, "{\"temperatureLowerC\":0,\"temperatureUpperC\":4,\"humidityLowerPct\":55}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("humidityUpperPct"));

        assertThat(versionCount()).isEqualTo(2);
    }

    @Test
    void anUnknownDeviceIs404() throws Exception {
        mvc.perform(get("/api/settings/thresholds/{id}", "NOPE").session(operator)).andExpect(status().isNotFound());
        submit(admin, "NOPE", CUSTOM).andExpect(status().isNotFound());
    }

    // ---- Reading -----------------------------------------------------------------------------------------------

    @Test
    void anyUserSeesEveryDeviceWithItsLocationDefault() throws Exception {
        mvc.perform(get("/api/settings/thresholds").session(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].deviceId").value(MEAT))
                .andExpect(jsonPath("$[0].locationCode").value("RAW-MEAT"))
                .andExpect(jsonPath("$[0].sessionId").value(meatSession))
                .andExpect(jsonPath("$[0].temperatureLowerC").value(0.0))
                .andExpect(jsonPath("$[0].temperatureUpperC").value(4.0))
                .andExpect(jsonPath("$[0].humidityLowerPct").value(55.0))
                .andExpect(jsonPath("$[0].humidityUpperPct").value(80.0))
                .andExpect(jsonPath("$[0].source").value("DEFAULT"))
                .andExpect(jsonPath("$[0].changedBy").isEmpty())
                .andExpect(jsonPath("$[0].pendingRequest").isEmpty())
                .andExpect(jsonPath("$[1].deviceId").value(FROZEN))
                .andExpect(jsonPath("$[1].temperatureLowerC").value(-25.0))
                .andExpect(jsonPath("$[1].temperatureUpperC").value(-18.0));
    }

    // ---- Administrator changes ---------------------------------------------------------------------------------

    @Test
    void anAdministratorChangeAppliesAtOnceIsAuditedAndUsedByLaterReadings() throws Exception {
        long before = currentVersion(MEAT);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        long earlier = ingestion.ingest(MEAT, new ReadingSubmission(MEAT, meatSession, now.plusSeconds(1), 3, 60))
                .reading().thresholdId();

        submit(admin, MEAT, CUSTOM)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.threshold.temperatureLowerC").value(1.0))
                .andExpect(jsonPath("$.threshold.humidityUpperPct").value(85.0))
                .andExpect(jsonPath("$.threshold.source").value("CUSTOM"))
                .andExpect(jsonPath("$.threshold.changedBy").value("admin1"))
                .andExpect(jsonPath("$.request").isEmpty());

        long after = currentVersion(MEAT);
        assertThat(after).isNotEqualTo(before);
        assertThat(earlier).isEqualTo(before);
        long later = ingestion.ingest(MEAT, new ReadingSubmission(MEAT, meatSession, now.plusSeconds(2), 3, 60))
                .reading().thresholdId();
        assertThat(later).isEqualTo(after);
        // The earlier reading keeps the version it was accepted under.
        assertThat(jdbc.sql("SELECT threshold_id FROM readings ORDER BY measured_at").query(Long.class).list())
                .containsExactly(before, after);

        AuditEvent event = latestAudit(AuditEventType.THRESHOLD_CHANGED);
        assertThat(event.targetType()).isEqualTo("THRESHOLD_SETTING");
        assertThat(event.targetId()).isEqualTo(String.valueOf(after));
        assertThat(event.details()).contains("device=" + MEAT, "old=T=[0.0, 4.0] C, RH=[55.0, 80.0] %",
                "new=T=[1.0, 5.0] C, RH=[60.0, 85.0] %", "source=ADMIN");
    }

    @Test
    void submittingTheCurrentLimitsIsUnchanged() throws Exception {
        submit(admin, MEAT, MEAT_DEFAULT).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"));
        submit(operator, MEAT, MEAT_DEFAULT).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNCHANGED"));

        assertThat(versionCount()).isEqualTo(2);
    }

    // ---- Operator requests -------------------------------------------------------------------------------------

    @Test
    void anOperatorChangeWaitsForApprovalAndLeavesTheLimitsAlone() throws Exception {
        long before = currentVersion(MEAT);

        long requestId = requestId(submit(operator, MEAT, CUSTOM)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.outcome").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.threshold.temperatureUpperC").value(4.0))
                .andExpect(jsonPath("$.request.status").value("PENDING"))
                .andExpect(jsonPath("$.request.temperatureUpperC").value(5.0))
                .andExpect(jsonPath("$.request.requestedBy").value("operator1"))
                .andExpect(jsonPath("$.request.baseThresholdId").value(before)));

        assertThat(currentVersion(MEAT)).isEqualTo(before);
        mvc.perform(get("/api/settings/thresholds/{id}", MEAT).session(otherOperator))
                .andExpect(jsonPath("$.pendingRequest.id").value(requestId))
                .andExpect(jsonPath("$.source").value("DEFAULT"));
        AuditEvent event = latestAudit(AuditEventType.THRESHOLD_CHANGE_REQUESTED);
        assertThat(event.targetId()).isEqualTo(String.valueOf(requestId));
        assertThat(event.details()).contains("new=T=[1.0, 5.0] C, RH=[60.0, 85.0] %");
    }

    @Test
    void repeatingARequestReturnsItAndChangingItReplacesIt() throws Exception {
        long first = requestId(submit(operator, MEAT, CUSTOM).andExpect(status().isAccepted()));
        long requested = countAudit(AuditEventType.THRESHOLD_CHANGE_REQUESTED);

        long same = requestId(submit(operator, MEAT, CUSTOM).andExpect(status().isAccepted()));
        assertThat(same).isEqualTo(first);
        assertThat(countAudit(AuditEventType.THRESHOLD_CHANGE_REQUESTED)).isEqualTo(requested);

        long second = requestId(submit(operator, MEAT, limits(1, 6, 60, 85)).andExpect(status().isAccepted()));
        assertThat(second).isNotEqualTo(first);
        mvc.perform(get("/api/settings/threshold-requests").session(operator).param("deviceId", MEAT))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[1].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.items[1].reviewedBy").value("operator1"));
    }

    @Test
    void anotherUsersPendingRequestBlocksANewOne() throws Exception {
        submit(operator, MEAT, CUSTOM).andExpect(status().isAccepted());

        submit(otherOperator, MEAT, limits(2, 6, 60, 85))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THRESHOLD_REQUEST_PENDING"));
        // Other devices are independent.
        submit(otherOperator, FROZEN, limits(-24, -18, 35, 80)).andExpect(status().isAccepted());
    }

    @Test
    void approvalAppliesTheRequestAndIsAudited() throws Exception {
        long before = currentVersion(MEAT);
        long requestId = requestId(submit(operator, MEAT, CUSTOM));

        review(admin, requestId, "approval", "{\"note\":\"Matches the supplier spec.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("APPROVED"))
                .andExpect(jsonPath("$.request.reviewedBy").value("admin1"))
                .andExpect(jsonPath("$.request.reviewNote").value("Matches the supplier spec."))
                .andExpect(jsonPath("$.request.appliedThresholdId").isNumber())
                .andExpect(jsonPath("$.threshold.temperatureUpperC").value(5.0))
                .andExpect(jsonPath("$.threshold.changedBy").value("admin1"))
                .andExpect(jsonPath("$.threshold.pendingRequest").isEmpty());

        long after = currentVersion(MEAT);
        assertThat(after).isNotEqualTo(before);
        assertThat(latestAudit(AuditEventType.THRESHOLD_CHANGE_APPROVED).targetId())
                .isEqualTo(String.valueOf(requestId));
        AuditEvent changed = latestAudit(AuditEventType.THRESHOLD_CHANGED);
        assertThat(changed.targetId()).isEqualTo(String.valueOf(after));
        assertThat(changed.details()).contains("source=REQUEST", "request=" + requestId, "requestedBy=operator1");

        review(admin, requestId, "approval", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THRESHOLD_REQUEST_NOT_PENDING"));
    }

    @Test
    void rejectionKeepsTheLimits() throws Exception {
        long before = currentVersion(MEAT);
        long requestId = requestId(submit(operator, MEAT, CUSTOM));

        review(admin, requestId, "rejection", "{\"note\":\"Too warm for raw meat.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("REJECTED"))
                .andExpect(jsonPath("$.request.reviewNote").value("Too warm for raw meat."))
                .andExpect(jsonPath("$.threshold.temperatureUpperC").value(4.0));

        assertThat(currentVersion(MEAT)).isEqualTo(before);
        assertThat(latestAudit(AuditEventType.THRESHOLD_CHANGE_REJECTED).actorId()).isNotNull();
        // The device is free for a new request.
        submit(otherOperator, MEAT, CUSTOM).andExpect(status().isAccepted());
    }

    @Test
    void reviewNotesAreBounded() throws Exception {
        long requestId = requestId(submit(operator, MEAT, CUSTOM));

        review(admin, requestId, "rejection", "{\"note\":\"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void operatorsCannotReviewRequests() throws Exception {
        long requestId = requestId(submit(operator, MEAT, CUSTOM));

        review(operator, requestId, "approval", null).andExpect(status().isForbidden());
        review(otherOperator, requestId, "rejection", null).andExpect(status().isForbidden());

        assertThat(latestAudit(AuditEventType.ACCESS_DENIED)).isNotNull();
        mvc.perform(get("/api/settings/thresholds/{id}", MEAT).session(admin))
                .andExpect(jsonPath("$.pendingRequest.status").value("PENDING"));
    }

    @Test
    void onlyTheRequesterCanCancel() throws Exception {
        long requestId = requestId(submit(operator, MEAT, CUSTOM));

        review(otherOperator, requestId, "cancellation", null).andExpect(status().isForbidden());
        review(admin, requestId, "cancellation", null).andExpect(status().isForbidden());
        review(operator, requestId, "cancellation", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("CANCELLED"))
                .andExpect(jsonPath("$.threshold.pendingRequest").isEmpty());
        review(operator, requestId, "cancellation", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("THRESHOLD_REQUEST_NOT_PENDING"));
        assertThat(latestAudit(AuditEventType.THRESHOLD_CHANGE_CANCELLED).targetId())
                .isEqualTo(String.valueOf(requestId));
    }

    @Test
    void anAdministratorChangeSupersedesThePendingRequest() throws Exception {
        long requestId = requestId(submit(operator, MEAT, CUSTOM));

        submit(admin, MEAT, limits(0, 3, 55, 80)).andExpect(status().isOk())
                .andExpect(jsonPath("$.threshold.pendingRequest").isEmpty());

        mvc.perform(get("/api/settings/threshold-requests").session(admin).param("status", "SUPERSEDED"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(requestId))
                .andExpect(jsonPath("$.items[0].reviewedBy").value("admin1"));
        assertThat(latestAudit(AuditEventType.THRESHOLD_CHANGED).details())
                .contains("supersededRequest=" + requestId);
    }

    @Test
    void operatorsListOnlyTheirOwnRequestsAndAdministratorsSeeAll() throws Exception {
        long own = requestId(submit(operator, MEAT, CUSTOM));
        requestId(submit(otherOperator, FROZEN, limits(-24, -18, 35, 80)));

        mvc.perform(get("/api/settings/threshold-requests").session(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(own));
        mvc.perform(get("/api/settings/threshold-requests").session(admin))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50));
        mvc.perform(get("/api/settings/threshold-requests").session(admin).param("status", "APPROVED,REJECTED"))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/settings/threshold-requests").session(admin).param("deviceId", FROZEN))
                .andExpect(jsonPath("$.items[0].requestedBy").value("operator2"));
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------

    static String limits(double tLow, double tHigh, double hLow, double hHigh) {
        return "{\"temperatureLowerC\":" + tLow + ",\"temperatureUpperC\":" + tHigh + ",\"humidityLowerPct\":" + hLow
                + ",\"humidityUpperPct\":" + hHigh + "}";
    }

    private ResultActions submit(MockHttpSession session, String deviceId, String body) throws Exception {
        return mvc.perform(put("/api/settings/thresholds/{id}", deviceId).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions review(MockHttpSession session, long requestId, String action, String body)
            throws Exception {
        var request = post("/api/settings/threshold-requests/{id}/" + action, requestId).session(session).with(csrf());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private static long requestId(ResultActions result) throws Exception {
        Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.request.id");
        return id.longValue();
    }

    private long currentVersion(String deviceId) {
        return jdbc.sql("""
                SELECT max(t.id) FROM threshold_settings t
                JOIN storage_sessions s ON s.id = t.session_id AND s.ended_at IS NULL
                WHERE s.device_id = ?""").param(deviceId).query(Long.class).single();
    }

    private long versionCount() {
        return jdbc.sql("SELECT count(*) FROM threshold_settings").query(Long.class).single();
    }

    private long countAudit(AuditEventType type) {
        return auditEvents.findRecent(500).stream().filter(e -> e.eventType() == type).count();
    }

    private AuditEvent latestAudit(AuditEventType type) {
        return auditEvents.findRecent(100).stream()
                .filter(e -> e.eventType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No audit event of type " + type));
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username).param("password", PASSWORD).session(session).with(csrf()))
                .andExpect(status().isOk());
        return session;
    }
}
