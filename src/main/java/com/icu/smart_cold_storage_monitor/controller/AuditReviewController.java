package com.icu.smart_cold_storage_monitor.controller;

import com.icu.smart_cold_storage_monitor.controller.dto.AuditEventResponse;
import com.icu.smart_cold_storage_monitor.controller.dto.PageResponse;
import com.icu.smart_cold_storage_monitor.model.AuditEventType;
import com.icu.smart_cold_storage_monitor.model.AuditResult;
import com.icu.smart_cold_storage_monitor.service.AuditReviewService;
import com.icu.smart_cold_storage_monitor.service.PageQuery;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrator audit review, restricted to ADMIN by {@code SecurityConfig}. Read-only by design: there is no
 * endpoint that updates or deletes audit records.
 *
 * <p>{@code GET /api/audit-events} query parameters, all optional:
 * <ul>
 *   <li>{@code from} / {@code to}: ISO 8601 UTC instants, e.g. {@code 2026-11-06T00:00:00Z}; {@code from} is
 *       inclusive, {@code to} exclusive.</li>
 *   <li>{@code actor}: exact username of the acting user or of a failed login attempt.</li>
 *   <li>{@code eventType}, {@code result}: repeat or comma-separate to match several values.</li>
 *   <li>{@code page} (zero-based, default 0) and {@code size} (default 50, capped at 200).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/audit-events")
public class AuditReviewController {

    private final AuditReviewService reviews;

    public AuditReviewController(AuditReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    public PageResponse<AuditEventResponse> search(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String actor,
            @RequestParam(name = "eventType", required = false) List<AuditEventType> eventTypes,
            @RequestParam(name = "result", required = false) List<AuditResult> results,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return PageResponse.from(
                reviews.search(from, to, actor, eventTypes, results, PageQuery.of(page, size)),
                AuditEventResponse::from);
    }
}
