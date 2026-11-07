package com.icu.smart_cold_storage_monitor.monitoring;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("monitoring-seven-zone")
@AutoConfigureMockMvc
class SevenZoneReplayTest extends SqliteIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void replaysSeparateFruitDevicesUsingTheExpandedInventory() throws Exception {
        mvc.perform(get("/api/monitoring/replay/assessments")
                .param("at", "2026-11-05T17:10:00Z").with(user("operator").roles("OPERATOR")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.lots", hasSize(28)))
            .andExpect(jsonPath("$.rejectedSensorRecords").value(0))
            .andExpect(jsonPath("$.lots[?(@.environmentStatus == 'UNKNOWN')]").isEmpty())
            .andExpect(jsonPath("$.lots[?(@.environmentStatus == 'ACTION_REQUIRED')]", hasSize(10)))
            .andExpect(jsonPath("$.lots[?(@.lot.location == 'FRUIT-COLD-BERRY' && @.deviceId == 'ESP32-SIM-006' && @.environmentStatus == 'OK')]", hasSize(2)))
            .andExpect(jsonPath("$.lots[?(@.lot.location == 'FRUIT-TROPICAL' && @.deviceId == 'ESP32-SIM-007' && @.environmentStatus == 'ACTION_REQUIRED')]", hasSize(2)));
    }
}
