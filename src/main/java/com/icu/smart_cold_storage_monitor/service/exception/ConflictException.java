package com.icu.smart_cold_storage_monitor.service.exception;

/** The request conflicts with current state (mapped to 409 with {@link #getCode()}). */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
