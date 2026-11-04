package com.icu.smart_cold_storage_monitor.security;

import com.icu.smart_cold_storage_monitor.web.JsonResponseWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.session.SessionInformationExpiredEvent;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.stereotype.Component;

/** JSON 401 for a request made on a session that was ended by a password reset or account deactivation. */
@Component
public class JsonSessionExpiredStrategy implements SessionInformationExpiredStrategy {

    private final JsonResponseWriter writer;

    public JsonSessionExpiredStrategy(JsonResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void onExpiredSessionDetected(SessionInformationExpiredEvent event) throws IOException {
        writer.writeError(event.getRequest(), event.getResponse(), HttpServletResponse.SC_UNAUTHORIZED,
                "SESSION_EXPIRED", "Your session has ended. Please sign in again.");
    }
}
