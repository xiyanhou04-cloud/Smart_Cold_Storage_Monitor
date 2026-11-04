package com.icu.smart_cold_storage_monitor.service.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * A signed-in user may call the endpoint but this specific action is not allowed for them, e.g. an administrator
 * resetting another administrator's password. Mapped to 403 and audited as ACCESS_DENIED, like any other
 * {@link AccessDeniedException}.
 */
public class OperationNotPermittedException extends AccessDeniedException {

    public OperationNotPermittedException(String message) {
        super(message);
    }
}
