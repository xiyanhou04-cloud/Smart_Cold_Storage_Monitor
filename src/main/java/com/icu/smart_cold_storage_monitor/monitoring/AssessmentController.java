package com.icu.smart_cold_storage_monitor.monitoring;

import java.time.Instant;
import org.springframework.web.bind.annotation.*;
import com.icu.smart_cold_storage_monitor.monitoring.MonitoringModels.Report;
import com.icu.smart_cold_storage_monitor.web.ApiError;
import com.icu.smart_cold_storage_monitor.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

/** Existing session authentication protects both endpoints through the existing API security rules. */
@RestController
@RequestMapping("/api/monitoring")
public class AssessmentController {
    private final AssessmentService service;
    public AssessmentController(AssessmentService service) {this.service=service;}
    @GetMapping("/assessments")
    public Report live() {return service.evaluate(null);}
    @GetMapping("/replay/assessments")
    public Report replay(@RequestParam String at) {
        try {return service.evaluate(Instant.parse(at));}
        catch(java.time.format.DateTimeParseException ex) {throw new com.icu.smart_cold_storage_monitor.service.exception.InvalidInputException("at", "Use an ISO 8601 timestamp with timezone.");}
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> unavailable(IllegalStateException ex,HttpServletRequest request) {
        return ResponseEntity.status(503).body(ApiError.of("INVENTORY_SOURCE_UNAVAILABLE",
            "The inventory source could not be read; assessment is unavailable.",RequestIdFilter.requestId(request)));
    }
}
