package com.icu.smart_cold_storage_monitor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.User;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.repository.UserRepository;
import com.icu.smart_cold_storage_monitor.service.UserAccountService;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Administrator account management through the real security chain and a temporary SQLite database. */
@AutoConfigureMockMvc
class UserAdminApiTest extends SqliteIntegrationTest {

    private static final String PASSWORD = "correct-horse-1";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountService accounts;

    @Autowired
    UserRepository users;

    @Autowired
    AuditEventRepository auditEvents;

    User admin;
    User otherAdmin;
    User operator;

    @BeforeEach
    void createUsers() {
        admin = accounts.createUser("admin1", PASSWORD, Role.ADMIN, null).user();
        otherAdmin = accounts.createUser("admin2", PASSWORD, Role.ADMIN, null).user();
        operator = accounts.createUser("operator1", PASSWORD, Role.OPERATOR, null).user();
    }

    // ---- Access control ----------------------------------------------------------------------------------------

    @Test
    void anonymousCannotManageUsers() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(createRequest("{\"username\":\"x_user\",\"role\":\"OPERATOR\"}").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void operatorIsForbiddenAndDenialIsAudited() throws Exception {
        MockHttpSession session = login("operator1", PASSWORD);

        mvc.perform(get("/api/users").session(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(createRequest("{\"username\":\"sneaky\",\"role\":\"ADMIN\"}").session(session).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users/{id}/password-reset", operator.id()).session(session).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(users.existsByUsername("sneaky")).isFalse();
        assertThat(latestAudit(AuditEventType.ACCESS_DENIED).actorId()).isEqualTo(operator.id());
    }

    @Test
    void mutationsRequireCsrf() throws Exception {
        MockHttpSession session = login("admin1", PASSWORD);

        mvc.perform(createRequest("{\"username\":\"newbie\",\"role\":\"OPERATOR\"}").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/users/{id}/deactivation", operator.id()).session(session))
                .andExpect(status().isForbidden());

        assertThat(users.existsByUsername("newbie")).isFalse();
        assertThat(users.findById(operator.id()).orElseThrow().enabled()).isTrue();
    }

    @Test
    void adminListsUsersWithoutPasswordHashes() throws Exception {
        MockHttpSession session = login("admin1", PASSWORD);

        mvc.perform(get("/api/users").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].username").value("admin1"))
                .andExpect(jsonPath("$[2].role").value("OPERATOR"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$[0].createdAt").isString());
    }

    // ---- 1. Create accounts ------------------------------------------------------------------------------------

    @Test
    void adminCreatesOperatorWithSuppliedPassword() throws Exception {
        MockHttpSession session = login("admin1", PASSWORD);

        MvcResult result = mvc.perform(createRequest(
                        "{\"username\":\"operator2\",\"role\":\"OPERATOR\",\"password\":\"chosen-pass-1\"}")
                        .session(session).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/users/")))
                .andExpect(jsonPath("$.username").value("operator2"))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.generatedPassword").doesNotExist())
                .andReturn();

        login("operator2", "chosen-pass-1");
        long newId = ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
        AuditEvent event = latestAudit(AuditEventType.USER_CREATED);
        assertThat(event.actorId()).isEqualTo(admin.id());
        assertThat(event.targetId()).isEqualTo(String.valueOf(newId));
        assertThat(event.details()).doesNotContain("chosen-pass-1");
    }

    @Test
    void adminCreatesAdministratorWithGeneratedPassword() throws Exception {
        MockHttpSession session = login("admin1", PASSWORD);

        MvcResult result = mvc.perform(createRequest("{\"username\":\"admin3\",\"role\":\"ADMIN\"}")
                        .session(session).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.generatedPassword").isString())
                .andReturn();

        String generated = JsonPath.read(result.getResponse().getContentAsString(), "$.generatedPassword");
        assertThat(generated).hasSizeGreaterThanOrEqualTo(20);
        mvc.perform(loginRequest("admin3", generated).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
        assertThat(latestAudit(AuditEventType.USER_CREATED).details()).doesNotContain(generated);
    }

    @Test
    void duplicateUsernameIsConflict() throws Exception {
        MockHttpSession session = login("admin1", PASSWORD);

        mvc.perform(createRequest("{\"username\":\"operator1\",\"role\":\"OPERATOR\"}").session(session).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void invalidCreateRequestsAreRejected() throws Exception {
        MockHttpSession session = login("admin1", PASSWORD);

        mvc.perform(createRequest("{\"username\":\"bad name\",\"role\":\"OPERATOR\"}").session(session).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("username"));
        mvc.perform(createRequest("{\"username\":\"no_role\"}").session(session).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("role"));
        mvc.perform(createRequest("{\"username\":\"bad_role\",\"role\":\"ROOT\"}").session(session).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(createRequest("{\"username\":\"short_pw\",\"role\":\"OPERATOR\",\"password\":\"123\"}")
                        .session(session).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));

        assertThat(users.findAll()).hasSize(3);
    }

    // ---- 2. Reset passwords ------------------------------------------------------------------------------------

    @Test
    void adminResetsOperatorPasswordAndOperatorSessionEnds() throws Exception {
        MockHttpSession operatorSession = login("operator1", PASSWORD);
        MockHttpSession adminSession = login("admin1", PASSWORD);

        mvc.perform(resetRequest(operator.id(), "{\"newPassword\":\"fresh-pass-1\"}").session(adminSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("operator1"))
                .andExpect(jsonPath("$.generatedPassword").doesNotExist());

        mvc.perform(get("/api/auth/me").session(operatorSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mvc.perform(loginRequest("operator1", PASSWORD).with(csrf())).andExpect(status().isUnauthorized());
        login("operator1", "fresh-pass-1");

        AuditEvent event = latestAudit(AuditEventType.PASSWORD_RESET);
        assertThat(event.actorId()).isEqualTo(admin.id());
        assertThat(event.targetId()).isEqualTo(String.valueOf(operator.id()));
        assertThat(event.details()).doesNotContain("fresh-pass-1");
    }

    @Test
    void resetWithoutBodyGeneratesPassword() throws Exception {
        MockHttpSession adminSession = login("admin1", PASSWORD);

        MvcResult result = mvc.perform(post("/api/users/{id}/password-reset", operator.id())
                        .session(adminSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedPassword").isString())
                .andReturn();

        String generated = JsonPath.read(result.getResponse().getContentAsString(), "$.generatedPassword");
        login("operator1", generated);
    }

    @Test
    void adminResetsOwnPasswordAndKeepsCurrentSession() throws Exception {
        MockHttpSession otherSession = login("admin1", PASSWORD);
        MockHttpSession adminSession = login("admin1", PASSWORD);

        mvc.perform(resetRequest(admin.id(), "{\"newPassword\":\"my-new-pass-1\"}").session(adminSession).with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me").session(adminSession)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(otherSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        login("admin1", "my-new-pass-1");
    }

    @Test
    void adminCannotResetAnotherAdministratorsPassword() throws Exception {
        MockHttpSession adminSession = login("admin1", PASSWORD);

        mvc.perform(resetRequest(otherAdmin.id(), "{\"newPassword\":\"hijack-pass-1\"}")
                        .session(adminSession).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Administrators cannot reset another administrator's password."));

        login("admin2", PASSWORD); // unchanged
        mvc.perform(loginRequest("admin2", "hijack-pass-1").with(csrf())).andExpect(status().isUnauthorized());
        AuditEvent denial = latestAudit(AuditEventType.ACCESS_DENIED);
        assertThat(denial.actorId()).isEqualTo(admin.id());
        assertThat(denial.details()).startsWith("FORBIDDEN:");
        assertThat(auditEvents.findRecent(50)).noneMatch(e -> e.eventType() == AuditEventType.PASSWORD_RESET);
    }

    @Test
    void resetOfUnknownUserIsNotFound() throws Exception {
        MockHttpSession adminSession = login("admin1", PASSWORD);

        mvc.perform(resetRequest(999_999L, "{}").session(adminSession).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ---- 3. Deactivate operators -------------------------------------------------------------------------------

    @Test
    void adminDeactivatesOperatorWhoIsSignedOutAndCannotSignIn() throws Exception {
        MockHttpSession operatorSession = login("operator1", PASSWORD);
        MockHttpSession adminSession = login("admin1", PASSWORD);

        mvc.perform(post("/api/users/{id}/deactivation", operator.id()).session(adminSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(get("/api/auth/me").session(operatorSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mvc.perform(loginRequest("operator1", PASSWORD).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        AuditEvent event = latestAudit(AuditEventType.USER_DEACTIVATED);
        assertThat(event.actorId()).isEqualTo(admin.id());
        assertThat(event.targetId()).isEqualTo(String.valueOf(operator.id()));
    }

    @Test
    void repeatedDeactivationIsIdempotent() throws Exception {
        MockHttpSession adminSession = login("admin1", PASSWORD);

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/users/{id}/deactivation", operator.id()).session(adminSession).with(csrf()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enabled").value(false));
        }

        assertThat(auditEvents.findRecent(50))
                .filteredOn(e -> e.eventType() == AuditEventType.USER_DEACTIVATED)
                .hasSize(1);
    }

    @Test
    void administratorsCannotBeDeactivated() throws Exception {
        MockHttpSession adminSession = login("admin1", PASSWORD);

        mvc.perform(post("/api/users/{id}/deactivation", otherAdmin.id()).session(adminSession).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users/{id}/deactivation", admin.id()).session(adminSession).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(users.findById(otherAdmin.id()).orElseThrow().enabled()).isTrue();
        assertThat(users.findById(admin.id()).orElseThrow().enabled()).isTrue();
    }

    @Test
    void deactivatedAccountPasswordCannotBeReset() throws Exception {
        MockHttpSession adminSession = login("admin1", PASSWORD);
        mvc.perform(post("/api/users/{id}/deactivation", operator.id()).session(adminSession).with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(resetRequest(operator.id(), "{}").session(adminSession).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DEACTIVATED"));
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder createRequest(String json) {
        return post("/api/users").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static MockHttpServletRequestBuilder resetRequest(long id, String json) {
        return post("/api/users/{id}/password-reset", id).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static MockHttpServletRequestBuilder loginRequest(String username, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", username)
                .param("password", password);
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        ResultActions result = mvc.perform(loginRequest(username, password).session(session).with(csrf()));
        result.andExpect(status().isOk());
        return session;
    }

    private AuditEvent latestAudit(AuditEventType type) {
        return auditEvents.findRecent(100).stream()
                .filter(e -> e.eventType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No audit event of type " + type));
    }
}
