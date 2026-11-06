package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.model.AuditEntry;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import com.icu.smart_cold_storage_monitor.repository.AuditEventFilter;
import com.icu.smart_cold_storage_monitor.repository.AuditEventRepository;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Administrator review of audit records (FR-08, NIST AU-6). Read-only: the audit trail has no update or delete
 * path. Access is restricted to ADMIN by {@code SecurityConfig}.
 */
@Service
public class AuditReviewService {

    private final AuditEventRepository repository;

    public AuditReviewService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns one page of matching events, most recent first.
     *
     * @param from       inclusive lower time bound, or {@code null}
     * @param to         exclusive upper time bound, or {@code null}; must be after {@code from} when both are set
     * @param actor      username of the actor or of a failed login attempt; blank means no restriction
     * @param eventTypes event types to include; empty means all
     * @param results    outcomes to include; empty means all
     */
    // Deliberately not @Transactional: transactions use BEGIN IMMEDIATE, which would take SQLite's single write lock
    // for a pure read. The count and the page are separate autocommit reads, so an event committed in between can
    // make totalItems differ by one from the rows returned; that is acceptable for review screens.
    public PagedResult<AuditEntry> search(Instant from, Instant to, String actor, List<AuditEventType> eventTypes,
                                          List<AuditResult> results, PageQuery pageQuery) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new InvalidInputException("to", "'to' must be later than 'from'.");
        }
        String actorName = actor == null || actor.isBlank() ? null : actor.strip();
        if (actorName != null && actorName.length() > AuditService.MAX_ATTEMPTED_USERNAME_LENGTH) {
            throw new InvalidInputException("actor",
                    "Actor must be at most " + AuditService.MAX_ATTEMPTED_USERNAME_LENGTH + " characters.");
        }
        AuditEventFilter filter = new AuditEventFilter(from, to, actorName,
                eventTypes == null ? Set.of() : Set.copyOf(eventTypes),
                results == null ? Set.of() : Set.copyOf(results));

        long total = repository.count(filter);
        List<AuditEntry> items = pageQuery.offset() >= total
                ? List.of()
                : repository.search(filter, pageQuery.offset(), pageQuery.size());
        return new PagedResult<>(items, pageQuery.page(), pageQuery.size(), total);
    }
}
