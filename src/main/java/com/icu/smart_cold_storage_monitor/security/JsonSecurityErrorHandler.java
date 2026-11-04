package com.icu.smart_cold_storage_monitor.security;

import com.icu.smart_cold_storage_monitor.service.AuditService;
import com.icu.smart_cold_storage_monitor.service.exception.OperationNotPermittedException;
import com.icu.smart_cold_storage_monitor.web.JsonResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/**
 * JSON 401 for unauthenticated API access and JSON 403 (with an ACCESS_DENIED audit event) for insufficient
 * permissions or a missing/invalid CSRF token. Never redirects to a login page.
 */
@Component
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonSecurityErrorHandler.class);

    private final AuditService audit;
    private final JsonResponseWriter writer;

    public JsonSecurityErrorHandler(AuditService audit, JsonResponseWriter writer) {
        this.audit = audit;
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writer.writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in to access this resource.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        boolean csrf = accessDeniedException instanceof CsrfException;
        boolean businessRule = accessDeniedException instanceof OperationNotPermittedException;
        String reason = csrf ? "CSRF_INVALID"
                : businessRule ? "FORBIDDEN: " + accessDeniedException.getMessage()
                : "FORBIDDEN";
        try {
            audit.recordAccessDenied(currentUserId(), request.getMethod() + " " + request.getRequestURI(), reason);
        } catch (RuntimeException e) {
            // The request is denied either way; log the audit gap rather than turning the 403 into a 500.
            log.error("Audit write failed for access denial", e);
        }
        if (csrf) {
            writer.writeError(request, response, HttpServletResponse.SC_FORBIDDEN, "CSRF_INVALID",
                    "Missing or invalid CSRF token. Fetch one from GET /api/auth/csrf.");
        } else if (businessRule) {
            writer.writeError(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    accessDeniedException.getMessage());
        } else {
            writer.writeError(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "You do not have permission to perform this action.");
        }
    }

    private static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
            return principal.getId();
        }
        return null;
    }
}
