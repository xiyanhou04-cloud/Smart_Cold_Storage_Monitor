package com.icu.smart_cold_storage_monitor.security;

import com.icu.smart_cold_storage_monitor.controller.dto.CurrentUserResponse;
import com.icu.smart_cold_storage_monitor.service.AuditService;
import com.icu.smart_cold_storage_monitor.web.JsonResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * JSON responses and audit events for {@code POST /api/auth/login}.
 *
 * <p>Login audit events are written independently of any business transaction. If the success event cannot be
 * stored, the new session is discarded and the client receives a 500, so a login is never reported as successful
 * without its audit record.
 */
@Component
public class JsonLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonLoginHandler.class);

    private final AuditService audit;
    private final JsonResponseWriter writer;

    public JsonLoginHandler(AuditService audit, JsonResponseWriter writer) {
        this.audit = audit;
        this.writer = writer;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();
        try {
            audit.recordLoginSuccess(principal.getId());
        } catch (RuntimeException e) {
            log.error("Audit write failed for login success; discarding the new session", e);
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            writer.writeError(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "AUDIT_UNAVAILABLE",
                    "Login could not be recorded; please try again.");
            return;
        }
        writer.write(response, HttpServletResponse.SC_OK, CurrentUserResponse.from(principal));
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        // Only the username is read from the request; the password is never logged or stored.
        String attemptedUsername = request.getParameter("username");
        try {
            audit.recordLoginFailure(attemptedUsername, failureReason(exception));
        } catch (RuntimeException e) {
            log.error("Audit write failed for login failure", e);
            writer.writeError(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "AUDIT_UNAVAILABLE",
                    "Login attempt could not be recorded; please try again.");
            return;
        }
        // One generic message for every cause, so responses do not reveal which usernames exist or are disabled.
        writer.writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_CREDENTIALS",
                "Invalid username or password.");
    }

    static String failureReason(AuthenticationException exception) {
        if (exception instanceof BadCredentialsException) {
            return "BAD_CREDENTIALS";
        }
        if (exception instanceof DisabledException) {
            return "ACCOUNT_DISABLED";
        }
        if (exception instanceof LockedException) {
            return "ACCOUNT_LOCKED";
        }
        return exception.getClass().getSimpleName();
    }
}
