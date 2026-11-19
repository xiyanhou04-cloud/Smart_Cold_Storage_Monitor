package com.icu.smart_cold_storage_monitor.service;

import com.icu.smart_cold_storage_monitor.model.Prediction;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.repository.PredictionFilter;
import com.icu.smart_cold_storage_monitor.repository.PredictionRepository;
import com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Risk prediction history (FR-05, FR-08) for any signed-in user. */
@Service
public class PredictionQueryService {

    private final PredictionRepository predictions;

    public PredictionQueryService(PredictionRepository predictions) {
        this.predictions = predictions;
    }

    /**
     * Returns one page of matching predictions, most recently measured reading first. An unknown device id simply
     * matches nothing.
     *
     * @param from inclusive lower bound on the scored reading's measured time, or {@code null}
     * @param to   exclusive upper bound on it, or {@code null}; must be after {@code from}
     */
    // Deliberately not @Transactional: transactions use BEGIN IMMEDIATE, which would take SQLite's single write lock
    // for a pure read. The count and the page are separate autocommit reads, so they may differ by a prediction
    // stored in between.
    public PagedResult<Prediction> search(List<String> deviceIds, List<RiskLevel> riskLevels, Instant from,
                                          Instant to, PageQuery pageQuery) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new InvalidInputException("to", "'to' must be later than 'from'.");
        }
        Set<String> devices = deviceIds == null ? Set.of() : deviceIds.stream()
                .filter(Objects::nonNull).map(String::strip).filter(id -> !id.isEmpty())
                .collect(Collectors.toSet());
        Set<RiskLevel> levels = riskLevels == null ? Set.of() : riskLevels.stream()
                .filter(Objects::nonNull).collect(Collectors.toSet());
        PredictionFilter filter = new PredictionFilter(devices, levels, from, to);
        long total = predictions.count(filter);
        List<Prediction> items = pageQuery.offset() >= total
                ? List.of()
                : predictions.search(filter, pageQuery.offset(), pageQuery.size());
        return new PagedResult<>(items, pageQuery.page(), pageQuery.size(), total);
    }
}
