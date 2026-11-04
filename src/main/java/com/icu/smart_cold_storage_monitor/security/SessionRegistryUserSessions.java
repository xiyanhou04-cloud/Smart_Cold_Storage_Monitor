package com.icu.smart_cold_storage_monitor.security;

import com.icu.smart_cold_storage_monitor.service.UserSessions;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * Expires sessions tracked by Spring Security's {@link SessionRegistry}. The {@code ConcurrentSessionFilter} then
 * rejects the next request on an expired session with 401 {@code SESSION_EXPIRED}.
 */
@Component
public class SessionRegistryUserSessions implements UserSessions {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistryUserSessions.class);

    private final SessionRegistry registry;

    public SessionRegistryUserSessions(SessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void expireAll(long userId, String keepSessionId) {
        int expired = 0;
        for (Object principal : registry.getAllPrincipals()) {
            if (principal instanceof AppUserPrincipal user && user.getId() == userId) {
                for (SessionInformation session : registry.getAllSessions(principal, false)) {
                    if (!Objects.equals(session.getSessionId(), keepSessionId)) {
                        session.expireNow();
                        expired++;
                    }
                }
            }
        }
        if (expired > 0) {
            log.info("Expired {} session(s) of user {}", expired, userId);
        }
    }
}
