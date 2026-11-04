package com.icu.smart_cold_storage_monitor.controller.dto;

/**
 * CSRF token for browser mutations. Send {@code token} in the {@code headerName} header (or the
 * {@code parameterName} form field) of every POST/PUT/DELETE, including login and logout.
 */
public record CsrfTokenResponse(String headerName, String parameterName, String token) {
}
