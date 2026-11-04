package com.icu.smart_cold_storage_monitor.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Writes JSON bodies from servlet-level code (security handlers) that runs outside Spring MVC. */
@Component
public class JsonResponseWriter {

    private final ObjectMapper objectMapper;

    public JsonResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    public void writeError(HttpServletRequest request, HttpServletResponse response, int status, String code,
                           String message) throws IOException {
        write(response, status, ApiError.of(code, message, RequestIdFilter.requestId(request)));
    }
}
