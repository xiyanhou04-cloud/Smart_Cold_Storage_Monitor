package com.icu.smart_cold_storage_monitor.security;

import com.icu.smart_cold_storage_monitor.service.AuditService;
import com.icu.smart_cold_storage_monitor.web.JsonResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Responds to {@code POST /api/auth/logout} after Spring Security has invalidated the session: 204 and a LOGOUT
 * audit event for a signed-in user, 401 when there was no signed-in user.
 */
@Component
public class JsonLogoutSuccessHandler implements LogoutSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonLogoutSuccessHandler.class);

    private final AuditService audit;
    private final JsonResponseWriter writer;

    public JsonLogoutSuccessHandler(AuditService audit, JsonResponseWriter writer) {
        this.audit = audit;
        this.writer = writer;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                Authentication authentication) throws IOException {
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            writer.writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                    "Not signed in.");
            return;
        }
        try {
            audit.recordLogout(principal.getId());
        } catch (RuntimeException e) {
            // The session is already gone; report the audit gap instead of claiming a clean logout.
            log.error("Audit write failed for logout of user {}", principal.getId(), e);
            writer.writeError(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "AUDIT_UNAVAILABLE",
                    "Signed out, but the logout could not be recorded.");
            return;
        }
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
