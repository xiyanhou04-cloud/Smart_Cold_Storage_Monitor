package com.icu.smart_cold_storage_monitor.service.exception;

/** The requested resource does not exist (mapped to 404). */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
