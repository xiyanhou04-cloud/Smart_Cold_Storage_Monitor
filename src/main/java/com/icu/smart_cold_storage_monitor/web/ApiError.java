package com.icu.smart_cold_storage_monitor.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Error body shared by every API error response: a stable machine-readable {@code code}, a human-readable
 * {@code message}, per-field problems when relevant, and the request id also sent in the {@code X-Request-Id} header.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, List<FieldError> fieldErrors, String requestId) {

    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, List.of(), requestId);
    }

    public record FieldError(String field, String message) {
    }
}
