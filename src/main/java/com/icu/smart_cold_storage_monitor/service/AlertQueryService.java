package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.model.Alert;
import com.icu.smart_cold_storage_monitor.model.AlertKind;
import com.icu.smart_cold_storage_monitor.model.AlertState;
import com.icu.smart_cold_storage_monitor.repository.AlertFilter;
import com.icu.smart_cold_storage_monitor.repository.AlertRepository;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import com.icu.smart_cold_storage_monitor.service.exception.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Alert history and lookup (FR-08) for any signed-in user. */
@Service
public class AlertQueryService {

    private final AlertRepository alerts;

    public AlertQueryService(AlertRepository alerts) {
        this.alerts = alerts;
    }

    /**
     * Returns one page of matching alerts, most recently opened first. An unknown device id simply matches nothing.
     *
     * @param from inclusive lower bound on the opening time, or {@code null}
     * @param to   exclusive upper bound on the opening time, or {@code null}; must be after {@code from}
     */
    // Deliberately not @Transactional: transactions use BEGIN IMMEDIATE, which would take SQLite's single write lock
    // for a pure read. The count and the page are separate autocommit reads, so they may differ by an alert opened
    // in between.
    public PagedResult<Alert> search(List<String> deviceIds, List<AlertKind> kinds, AlertState state,
                                     Boolean acknowledged, Instant from, Instant to, PageQuery pageQuery) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new InvalidInputException("to", "'to' must be later than 'from'.");
        }
        Set<String> devices = deviceIds == null ? Set.of() : deviceIds.stream()
                .filter(Objects::nonNull).map(String::strip).filter(id -> !id.isEmpty())
                .collect(Collectors.toSet());
        AlertFilter filter = new AlertFilter(devices, kinds == null ? Set.of() : Set.copyOf(kinds), state,
                acknowledged, from, to);
        long total = alerts.count(filter);
        List<Alert> items = pageQuery.offset() >= total
                ? List.of()
                : alerts.search(filter, pageQuery.offset(), pageQuery.size());
        return new PagedResult<>(items, pageQuery.page(), pageQuery.size(), total);
    }

    public Alert get(long id) {
        return alerts.findById(id).orElseThrow(() -> new NotFoundException("Alert " + id + " does not exist."));
    }
}
