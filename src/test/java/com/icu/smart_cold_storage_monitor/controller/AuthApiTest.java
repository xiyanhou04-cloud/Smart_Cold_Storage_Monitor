package com.icu.smart_cold_storage_monitor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icu.smart_cold_storage_monitor.model.AuditEvent;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.User;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.repository.UserRepository;
import com.icu.smart_cold_storage_monitor.service.AuditService;
import com.icu.smart_cold_storage_monitor.service.UserAccountService;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** FR-01 login and logout through the real security filter chain and a temporary SQLite database. */
@AutoConfigureMockMvc
class AuthApiTest extends SqliteIntegrationTest {

    private static final String PASSWORD = "correct-horse-1";

    @Autowired
    MockMvc mvc;

    @Autowired
    UserAccountService accounts;

    @Autowired
    UserRepository users;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    AuditService auditService;

    User operator;

    @BeforeEach
    void createUsers() {
        operator = accounts.createUser("operator1", PASSWORD, Role.OPERATOR, null).user();
    }

    // ---- Full browser flow using the real CSRF endpoint ---------------------------------------------------------

    @Test
    void loginMeLogoutFlowWithCsrfFromEndpoint() throws Exception {
        MockHttpSession session = new MockHttpSession();

        String loginToken = fetchCsrf(session);
        mvc.perform(loginRequest("operator1", PASSWORD).session(session).header("X-CSRF-TOKEN", loginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("operator1"))
                .andExpect(jsonPath("$.role").value("OPERATOR"));

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("operator1"))
                .andExpect(jsonPath("$.role").value("OPERATOR"));

        // Login rotates the CSRF token: the pre-login token no longer works.
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", loginToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        String logoutToken = fetchCsrf(session);
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", logoutToken))
                .andExpect(status().isNoContent());

        // The prior session no longer grants access.
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void csrfEndpointIsPublicAndDescribesHeader() throws Exception {
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    // ---- Login -------------------------------------------------------------------------------------------------

    @Test
    void successfulLoginIsAudited() throws Exception {
        mvc.perform(loginRequest("operator1", PASSWORD).with(csrf())).andExpect(status().isOk());

        AuditEvent event = latestAudit(AuditEventType.LOGIN_SUCCESS);
        assertThat(event.actorId()).isEqualTo(operator.id());
        assertThat(event.result()).isEqualTo(AuditResult.SUCCESS);
        assertThat(event.targetId()).isEqualTo(String.valueOf(operator.id()));
    }

    @Test
    void wrongPasswordReturns401AndAuditsAttemptedUsernameWithoutPassword() throws Exception {
        mvc.perform(loginRequest("operator1", "wrong-password-9").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(header().exists("X-Request-Id"));

        AuditEvent event = latestAudit(AuditEventType.LOGIN_FAILURE);
        assertThat(event.actorId()).isNull();
        assertThat(event.attemptedUsername()).isEqualTo("operator1");
        assertThat(event.result()).isEqualTo(AuditResult.FAILURE);
        assertThat(event.details()).isEqualTo("BAD_CREDENTIALS");
        assertThat(allAuditText()).doesNotContain("wrong-password-9");
    }

    @Test
    void unknownUserGetsSameResponseAsWrongPassword() throws Exception {
        mvc.perform(loginRequest("nobody", PASSWORD).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid username or password."));

        assertThat(latestAudit(AuditEventType.LOGIN_FAILURE).attemptedUsername()).isEqualTo("nobody");
    }

    @Test
    void attemptedUsernameIsBoundedInAudit() throws Exception {
        String longName = "x".repeat(300);

        mvc.perform(loginRequest(longName, PASSWORD).with(csrf())).andExpect(status().isUnauthorized());

        assertThat(latestAudit(AuditEventType.LOGIN_FAILURE).attemptedUsername()).hasSize(64);
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        users.insert("disabled1", passwordEncoder.encode(PASSWORD), Role.OPERATOR, false, Instant.now());

        mvc.perform(loginRequest("disabled1", PASSWORD).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        assertThat(latestAudit(AuditEventType.LOGIN_FAILURE).details()).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void loginWithoutCsrfIsForbiddenAndAudited() throws Exception {
        mvc.perform(loginRequest("operator1", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        AuditEvent event = latestAudit(AuditEventType.ACCESS_DENIED);
        assertThat(event.result()).isEqualTo(AuditResult.DENIED);
        assertThat(event.targetId()).isEqualTo("POST /api/auth/login");
        assertThat(event.details()).isEqualTo("CSRF_INVALID");
        assertThat(auditEvents.findRecent(10)).noneMatch(e -> e.eventType() == AuditEventType.LOGIN_SUCCESS);
    }

    @Test
    void loginIsRejectedWhenSuccessAuditCannotBeWritten() throws Exception {
        doThrow(new IllegalStateException("audit storage down")).when(auditService).recordLoginSuccess(anyLong());
        MockHttpSession session = new MockHttpSession();

        mvc.perform(loginRequest("operator1", PASSWORD).session(session).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));

        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void adminLoginReportsAdminRole() throws Exception {
        accounts.createUser("admin1", PASSWORD, Role.ADMIN, null);

        mvc.perform(loginRequest("admin1", PASSWORD).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    // ---- Logout and protected access ---------------------------------------------------------------------------

    @Test
    void logoutIsAuditedWithActor() throws Exception {
        MockHttpSession session = login("operator1");

        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());

        AuditEvent event = latestAudit(AuditEventType.LOGOUT);
        assertThat(event.actorId()).isEqualTo(operator.id());
        assertThat(event.result()).isEqualTo(AuditResult.SUCCESS);
    }

    @Test
    void logoutWithoutCsrfIsForbiddenAndKeepsSession() throws Exception {
        MockHttpSession session = login("operator1");

        mvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void logoutWhenNotSignedInReturns401() throws Exception {
        mvc.perform(post("/api/auth/logout").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void getOnLogoutDoesNotLogOut() throws Exception {
        MockHttpSession session = login("operator1");

        mvc.perform(get("/api/auth/logout").session(session));

        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void meRequiresSignIn() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void loginRotatesSessionId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();

        // Spring Security only rotates a session the client actually presented, as a browser would via its cookie.
        mvc.perform(loginRequest("operator1", PASSWORD).session(session).with(csrf())
                        .with(request -> {
                            request.setRequestedSessionId(before);
                            request.setRequestedSessionIdValid(true);
                            return request;
                        }))
                .andExpect(status().isOk());

        assertThat(session.getId()).isNotEqualTo(before);
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder loginRequest(String username, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", username)
                .param("password", password);
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(loginRequest(username, PASSWORD).session(session).with(csrf())).andExpect(status().isOk());
        return session;
    }

    private String fetchCsrf(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private AuditEvent latestAudit(AuditEventType type) {
        return auditEvents.findRecent(50).stream()
                .filter(e -> e.eventType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No audit event of type " + type));
    }

    private String allAuditText() {
        List<AuditEvent> events = auditEvents.findRecent(200);
        return events.toString();
    }
}
