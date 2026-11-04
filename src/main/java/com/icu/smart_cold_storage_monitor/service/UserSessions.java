package com.icu.smart_cold_storage_monitor.service;

/** Ends a user's active browser sessions, e.g. after their password is reset or their account is deactivated. */
public interface UserSessions {

    /**
     * Expires every active session of the user except {@code keepSessionId}.
     *
     * @param keepSessionId session to leave active (the caller's own), or {@code null} to expire all
     */
    void expireAll(long userId, String keepSessionId);
}
