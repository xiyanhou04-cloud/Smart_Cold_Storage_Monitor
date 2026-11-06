package com.icu.smart_cold_storage_monitor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.User;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.service.UserAccountService;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** FR-08 administrator audit review ({@code GET /api/audit-events}) through the real security chain. */
@AutoConfigureMockMvc
class AuditReviewApiTest extends SqliteIntegrationTest {

    private static final String PASSWORD = "correct-horse-1";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountService accounts;

    @Autowired
    AuditEventRepository auditEvents;

    User admin;
    User operator;

    @BeforeEach
    void createUsers() {
        admin = accounts.createUser("admin1", PASSWORD, Role.ADMIN, null).user();
        operator = accounts.createUser("operator1", PASSWORD, Role.OPERATOR, null).user();
    }

    // ---- Access control ----------------------------------------------------------------------------------------

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(get("/api/audit-events"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void operatorIsForbiddenAndDenialIsAudited() throws Exception {
        MockHttpSession session = login("operator1");

        mvc.perform(get("/api/audit-events").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(auditEvents.findRecent(10)).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo(AuditEventType.ACCESS_DENIED);
            assertThat(e.actorId()).isEqualTo(operator.id());
            assertThat(e.targetId()).isEqualTo("GET /api/audit-events");
        });
    }

    @Test
    void auditRecordsCannotBeModifiedThroughTheApi() throws Exception {
        MockHttpSession session = login("admin1");

        mvc.perform(post("/api/audit-events").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/audit-events/1").session(session).with(csrf()))
                .andExpect(status().is4xxClientError());
        assertThat(auditEvents.findRecent(10)).isNotEmpty();
    }

    // ---- Listing and filters -----------------------------------------------------------------------------------

    @Test
    void adminListsEventsNewestFirstWithRequiredFields() throws Exception {
        MockHttpSession session = loginAdminWithFixtures();

        mvc.perform(get("/api/audit-events").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items.length()").value(5))
                // Newest first.
                .andExpect(jsonPath("$.items[0].eventType").value("LOGOUT"))
                .andExpect(jsonPath("$.items[0].occurredAt").value("2026-01-01T04:00:00Z"))
                .andExpect(jsonPath("$.items[4].eventType").value("LOGIN_SUCCESS"))
                // AU-3 content: actor, event, time, result, affected object.
                .andExpect(jsonPath("$.items[4].actorId").value(admin.id()))
                .andExpect(jsonPath("$.items[4].actorUsername").value("admin1"))
                .andExpect(jsonPath("$.items[4].result").value("SUCCESS"))
                .andExpect(jsonPath("$.items[4].targetType").value("USER"))
                .andExpect(jsonPath("$.items[4].targetId").value(String.valueOf(admin.id())))
                // Failed login: no actor, bounded attempted username, never a password.
                .andExpect(jsonPath("$.items[3].eventType").value("LOGIN_FAILURE"))
                .andExpect(jsonPath("$.items[3].actorId").isEmpty())
                .andExpect(jsonPath("$.items[3].attemptedUsername").value("ghost"))
                .andExpect(jsonPath("$.items[3].password").doesNotExist());
    }

    @Test
    void filtersByTimeWindowWithInclusiveFromAndExclusiveTo() throws Exception {
        MockHttpSession session = loginAdminWithFixtures();

        mvc.perform(get("/api/audit-events").session(session)
                        .param("from", "2026-01-01T01:00:00Z")
                        .param("to", "2026-01-01T03:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].eventType").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.items[1].eventType").value("LOGIN_FAILURE"));
    }

    @Test
    void filtersByActorIncludingFailedLoginAttempts() throws Exception {
        MockHttpSession session = loginAdminWithFixtures();

        mvc.perform(get("/api/audit-events").session(session).param("actor", "operator1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].eventType").value("LOGOUT"))
                .andExpect(jsonPath("$.items[1].eventType").value("ACCESS_DENIED"));

        mvc.perform(get("/api/audit-events").session(session).param("actor", "ghost"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].attemptedUsername").value("ghost"));

        mvc.perform(get("/api/audit-events").session(session).param("actor", "nobody"))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void filtersByEventTypeAndResult() throws Exception {
        MockHttpSession session = loginAdminWithFixtures();

        // Repeated parameter and comma-separated values both select several types.
        mvc.perform(get("/api/audit-events").session(session)
                        .param("eventType", "LOGIN_SUCCESS").param("eventType", "LOGIN_FAILURE"))
                .andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/audit-events").session(session).param("eventType", "LOGIN_SUCCESS,LOGIN_FAILURE"))
                .andExpect(jsonPath("$.totalItems").value(2));

        mvc.perform(get("/api/audit-events").session(session).param("result", "DENIED"))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].eventType").value("ACCESS_DENIED"));

        // Filters combine with AND.
        mvc.perform(get("/api/audit-events").session(session)
                        .param("eventType", "LOGIN_FAILURE").param("result", "SUCCESS"))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void realFailedLoginIsVisibleInReview() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "intruder").param("password", "not-the-password"))
                .andExpect(status().isUnauthorized());
        MockHttpSession session = login("admin1");

        mvc.perform(get("/api/audit-events").session(session)
                        .param("eventType", "LOGIN_FAILURE").param("actor", "intruder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].result").value("FAILURE"))
                .andExpect(jsonPath("$.items[0].attemptedUsername").value("intruder"))
                .andExpect(jsonPath("$.items[0].details").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("not-the-password"))));
    }

    // ---- Paging ------------------------------------------------------------------------------------------------

    @Test
    void pagesThroughResults() throws Exception {
        MockHttpSession session = loginAdminWithFixtures();

        mvc.perform(get("/api/audit-events").session(session).param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].eventType").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.items[1].eventType").value("LOGIN_FAILURE"));

        mvc.perform(get("/api/audit-events").session(session).param("size", "2").param("page", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void pageSizeIsCappedAt200() throws Exception {
        MockHttpSession session = loginAdminWithFixtures();

        mvc.perform(get("/api/audit-events").session(session).param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(200));
    }

    // ---- Validation --------------------------------------------------------------------------------------------

    @Test
    void rejectsInvalidParameters() throws Exception {
        MockHttpSession session = login("admin1");

        expectFieldError(get("/api/audit-events").session(session)
                .param("from", "2026-01-02T00:00:00Z").param("to", "2026-01-01T00:00:00Z"), "to");
        expectFieldError(get("/api/audit-events").session(session)
                .param("from", "2026-01-01T00:00:00Z").param("to", "2026-01-01T00:00:00Z"), "to");
        expectFieldError(get("/api/audit-events").session(session).param("from", "yesterday"), "from");
        expectFieldError(get("/api/audit-events").session(session).param("eventType", "NOT_AN_EVENT"), "eventType");
        expectFieldError(get("/api/audit-events").session(session).param("result", "MAYBE"), "result");
        expectFieldError(get("/api/audit-events").session(session).param("size", "0"), "size");
        expectFieldError(get("/api/audit-events").session(session).param("page", "-1"), "page");
        expectFieldError(get("/api/audit-events").session(session).param("actor", "x".repeat(65)), "actor");
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------

    /**
     * Signs in the admin, then replaces the audit table with five events at known times:
     * 00:00 admin1 LOGIN_SUCCESS, 01:00 ghost LOGIN_FAILURE, 02:00 operator1 ACCESS_DENIED,
     * 03:00 admin1 USER_CREATED, 04:00 operator1 LOGOUT.
     */
    private MockHttpSession loginAdminWithFixtures() throws Exception {
        MockHttpSession session = login("admin1");
        jdbc.sql("DELETE FROM audit_events").update();
        insert(admin.id(), null, AuditEventType.LOGIN_SUCCESS, AuditResult.SUCCESS, 0, "USER",
                String.valueOf(admin.id()), null);
        insert(null, "ghost", AuditEventType.LOGIN_FAILURE, AuditResult.FAILURE, 1, "USER", null,
                "BAD_CREDENTIALS");
        insert(operator.id(), null, AuditEventType.ACCESS_DENIED, AuditResult.DENIED, 2, "ENDPOINT",
                "GET /api/users", "FORBIDDEN");
        insert(admin.id(), null, AuditEventType.USER_CREATED, AuditResult.SUCCESS, 3, "USER", "99",
                "username=operator2, role=OPERATOR");
        insert(operator.id(), null, AuditEventType.LOGOUT, AuditResult.SUCCESS, 4, "USER",
                String.valueOf(operator.id()), null);
        return session;
    }

    private void insert(Long actorId, String attemptedUsername, AuditEventType type, AuditResult result,
                        int hoursAfterT0, String targetType, String targetId, String details) {
        auditEvents.insert(new AuditEvent(null, actorId, attemptedUsername, type,
                T0.plusSeconds(hoursAfterT0 * 3600L), result, targetType, targetId, details));
    }

    private void expectFieldError(MockHttpServletRequestBuilder request, String field) throws Exception {
        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                .andExpect(jsonPath("$.requestId").isString());
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(status().isOk());
        return session;
    }
}
