package com.icu.smart_cold_storage_monitor.monitoring;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;

@ActiveProfiles("monitoring-demo")
@AutoConfigureMockMvc
class MonitoringApiTest extends SqliteIntegrationTest {
    @Autowired MockMvc mvc;
    @Test void anonymousCannotReadInventoryAssessments() throws Exception {
        mvc.perform(get("/api/monitoring/assessments")).andExpect(status().isUnauthorized());
    }
    @Test void operatorCanReadReplayWithExplicitMode() throws Exception {
        mvc.perform(get("/api/monitoring/replay/assessments").param("at","2026-11-05T17:10:00Z").with(user("operator").roles("OPERATOR")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("REPLAY"))
            .andExpect(jsonPath("$.lots.length()").value(24))
            .andExpect(jsonPath("$.rejectedSensorRecords").value(0))
            .andExpect(jsonPath("$.lots[?(@.environmentStatus == 'UNKNOWN')]").isEmpty())
            .andExpect(jsonPath("$.lots[?(@.environmentStatus == 'ACTION_REQUIRED')]").value(org.hamcrest.Matchers.hasSize(8)))
            .andExpect(jsonPath("$.lots[?(@.lot.location == 'VEG-COOL')].reading.deviceId",
                org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("ESP32-SIM-003"))))
            .andExpect(jsonPath("$.lots[?(@.lot.location == 'VEG-COOL')].findings[*].code",
                org.hamcrest.Matchers.hasItem("HUMIDITY_OUT_OF_RANGE")));
    }
    @Test void malformedTimeReturns400() throws Exception {
        mvc.perform(get("/api/monitoring/replay/assessments").param("at","bad-time").with(user("operator").roles("OPERATOR"))).andExpect(status().isBadRequest());
    }
}
