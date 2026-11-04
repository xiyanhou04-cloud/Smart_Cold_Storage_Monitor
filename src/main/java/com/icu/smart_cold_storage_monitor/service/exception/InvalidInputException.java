package com.icu.smart_cold_storage_monitor.service.exception;

/** A request value breaks a business rule (mapped to 400 with a field error). */
public class InvalidInputException extends RuntimeException {

    private final String field;

    public InvalidInputException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
