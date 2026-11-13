package com.icu.smart_cold_storage_monitor.controller;

import com.icu.smart_cold_storage_monitor.config.DeviceIngestionSecurityConfig;
import com.icu.smart_cold_storage_monitor.controller.dto.ReadingRequest;
import com.icu.smart_cold_storage_monitor.controller.dto.ReadingResponse;
import com.icu.smart_cold_storage_monitor.security.DevicePrincipal;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService;
import com.icu.smart_cold_storage_monitor.service.ReadingIngestionService.IngestionResult;
import com.icu.smart_cold_storage_monitor.web.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Device ingestion endpoint; authenticated by {@code DeviceIngestionSecurityConfig}, never by a browser session. */
@Tag(name = "Sensor readings", description = "Device ingestion of temperature and humidity readings.")
@RestController
public class ReadingController {

    private final ReadingIngestionService ingestion;

    public ReadingController(ReadingIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping(DeviceIngestionSecurityConfig.READINGS_URL)
    @Operation(summary = "Submit a reading",
            description = "Called by a device (or the built-in simulator) with `Authorization: Device "
                    + "<deviceId>:<token>`. No session cookie or CSRF token. The body's deviceId must match the "
                    + "credential, the session must be the device's active session, and the timestamp must be newer "
                    + "than the device's latest reading.")
    @ApiResponse(responseCode = "201", description = "Reading stored")
    @ApiResponse(responseCode = "200", description = "Identical retry; the stored reading is returned")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or MALFORMED_REQUEST",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "INVALID_DEVICE_CREDENTIAL",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "deviceId does not match the credential",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Storage session not found for this device",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "READING_CONFLICT, OUT_OF_ORDER or SESSION_NOT_ACTIVE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReadingResponse> submit(@Valid @RequestBody ReadingRequest request,
                                                  @Parameter(hidden = true) @AuthenticationPrincipal
                                                  DevicePrincipal device) {
        IngestionResult result = ingestion.ingest(device.deviceId(), request.toSubmission());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ReadingResponse.from(result.reading()));
    }
}
