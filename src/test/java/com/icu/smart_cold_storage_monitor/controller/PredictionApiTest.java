package com.icu.smart_cold_storage_monitor.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icu.smart_cold_storage_monitor.ml.RiskPredictor.Estimate;
import com.icu.smart_cold_storage_monitor.ml.RiskPredictor.Quality;
import com.icu.smart_cold_storage_monitor.model.AlertSource;
import com.icu.smart_cold_storage_monitor.model.RiskLevel;
import com.icu.smart_cold_storage_monitor.model.Role;
import com.icu.smart_cold_storage_monitor.model.StorageSession;
import com.icu.smart_cold_storage_monitor.repository.PredictionRepository;
import com.icu.smart_cold_storage_monitor.repository.ReadingRepository;
import com.icu.smart_cold_storage_monitor.service.DeviceSessionService;
import com.icu.smart_cold_storage_monitor.service.UserAccountService;
import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Prediction history ({@code GET /api/predictions}) through the real security chain. Readings and their predictions
 * are stored together in one transaction, so the background prediction worker never sees them as pending.
 */
@AutoConfigureMockMvc
class PredictionApiTest extends SqliteIntegrationTest {

    static final String PASSWORD = "correct-horse-1";
    static final String MEAT = "ESP32-SIM-001";
    static final String FROZEN = "ESP32-SIM-005";
    static final String EGGS = "ESP32-SIM-004";
    static final String MODEL = "random-forest-ml2-v1";

    @Autowired MockMvc mvc;
    @Autowired UserAccountService accounts;
    @Autowired DeviceSessionService deviceSessions;
    @Autowired ReadingRepository readings;
    @Autowired PredictionRepository predictions;
    @Autowired PlatformTransactionManager transactionManager;

    MockHttpSession operator;
    Instant t0;
    long meatLow;
    long meatMedium;
    long meatHigh;
    long frozenMedium;
    long eggsLow;

    @BeforeEach
    void setUp() throws Exception {
        accounts.createUser("operator1", PASSWORD, Role.OPERATOR, null);
        var meat = deviceSessions.ensureDeviceWithActiveSession(MEAT, "Raw meat", "RAW-MEAT", "RAW-MEAT");
        var frozen = deviceSessions.ensureDeviceWithActiveSession(FROZEN, "Freezer", "FROZEN", "FROZEN");
        var eggs = deviceSessions.ensureDeviceWithActiveSession(EGGS, "Eggs", "EGGS", "EGGS");
        t0 = meat.startedAt().truncatedTo(ChronoUnit.MILLIS);
        // Measured order: meatLow, frozenMedium, meatMedium, eggsLow, meatHigh.
        meatLow = store(meat, 0, 3.5, RiskLevel.LOW, AlertSource.MODEL, Quality.OK, "NONE", "NONE", false);
        frozenMedium = store(frozen, 5, -16.0, RiskLevel.MEDIUM, AlertSource.MODEL, Quality.OK, "NONE", "NONE", false);
        meatMedium = store(meat, 10, 4.6, RiskLevel.MEDIUM, AlertSource.RULES, Quality.INCOMPLETE_HISTORY,
                "RUNNING_DATA_GAP", "RUNNING_DATA_GAP", true);
        eggsLow = store(eggs, 15, 3.0, RiskLevel.LOW, AlertSource.MODEL, Quality.OK, "NONE", "NONE", false);
        meatHigh = store(meat, 20, 6.0, RiskLevel.HIGH, AlertSource.MODEL, Quality.OK, "NONE", "RUNNING_DATA_GAP",
                true);
        operator = login("operator1");
    }

    @Test
    void needsASignedInUser() throws Exception {
        mvc.perform(get("/api/predictions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/predictions").session(operator)).andExpect(status().isOk());
    }

    @Test
    void listsEveryPredictionMostRecentlyMeasuredFirstWithItsReadingAndFeatures() throws Exception {
        mvc.perform(get("/api/predictions").session(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.items[0].readingId").value(meatHigh))
                .andExpect(jsonPath("$.items[1].readingId").value(eggsLow))
                .andExpect(jsonPath("$.items[2].readingId").value(meatMedium))
                .andExpect(jsonPath("$.items[3].readingId").value(frozenMedium))
                .andExpect(jsonPath("$.items[4].readingId").value(meatLow))
                .andExpect(jsonPath("$.items[2].deviceId").value(MEAT))
                .andExpect(jsonPath("$.items[2].measuredAt").value(t0.plusSeconds(10).toString()))
                .andExpect(jsonPath("$.items[2].temperatureC").value(4.6))
                .andExpect(jsonPath("$.items[2].humidityPct").value(70.0))
                .andExpect(jsonPath("$.items[2].riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.items[2].source").value("RULES"))
                .andExpect(jsonPath("$.items[2].modelOrRuleVersion").value(MODEL))
                .andExpect(jsonPath("$.items[2].predictedAt").isString())
                .andExpect(jsonPath("$.items[2].qualityFlag").value("INCOMPLETE_HISTORY"))
                .andExpect(jsonPath("$.items[2].features.observedAboveUpperSeconds").value(10.0))
                .andExpect(jsonPath("$.items[2].features.elapsedStorageSeconds").value(10.0))
                .andExpect(jsonPath("$.items[2].features.productType").value("RAW_MEAT"))
                .andExpect(jsonPath("$.items[2].historicalExposureIncomplete").value(true))
                .andExpect(jsonPath("$.items[2].currentGapReason").value("RUNNING_DATA_GAP"))
                .andExpect(jsonPath("$.items[2].lastGapReason").value("RUNNING_DATA_GAP"));
    }

    @Test
    void filtersBySeveralDevices() throws Exception {
        expectReadings(get("/api/predictions").param("deviceId", MEAT), meatHigh, meatMedium, meatLow);
        expectReadings(get("/api/predictions").param("deviceId", MEAT).param("deviceId", FROZEN),
                meatHigh, meatMedium, frozenMedium, meatLow);
        expectReadings(get("/api/predictions").param("deviceId", FROZEN + "," + EGGS), eggsLow, frozenMedium);
        expectReadings(get("/api/predictions").param("deviceId", "NO-SUCH-DEVICE"));
    }

    @Test
    void filtersBySeveralRiskLevels() throws Exception {
        expectReadings(get("/api/predictions").param("riskLevel", "HIGH"), meatHigh);
        expectReadings(get("/api/predictions").param("riskLevel", "MEDIUM").param("riskLevel", "HIGH"),
                meatHigh, meatMedium, frozenMedium);
        expectReadings(get("/api/predictions").param("riskLevel", "LOW,MEDIUM"),
                eggsLow, meatMedium, frozenMedium, meatLow);
    }

    @Test
    void filtersByMeasuredTimeWithAnInclusiveStartAndExclusiveEnd() throws Exception {
        expectReadings(get("/api/predictions").param("from", t0.plusSeconds(10).toString()),
                meatHigh, eggsLow, meatMedium);
        expectReadings(get("/api/predictions").param("to", t0.plusSeconds(10).toString()), frozenMedium, meatLow);
        expectReadings(get("/api/predictions").param("from", t0.plusSeconds(5).toString())
                .param("to", t0.plusSeconds(15).toString()), meatMedium, frozenMedium);
    }

    @Test
    void combinesFilters() throws Exception {
        expectReadings(get("/api/predictions").param("deviceId", MEAT + "," + EGGS)
                .param("riskLevel", "MEDIUM,HIGH").param("from", t0.plusSeconds(1).toString()), meatHigh, meatMedium);
    }

    @Test
    void pages() throws Exception {
        mvc.perform(get("/api/predictions").param("size", "2").param("page", "1").session(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].readingId").value(meatMedium))
                .andExpect(jsonPath("$.items[1].readingId").value(frozenMedium))
                .andExpect(jsonPath("$.totalItems").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/predictions").param("page", "9").session(operator))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/predictions").param("size", "0").session(operator)).andExpect(status().isBadRequest());
    }

    @Test
    void invalidParametersAre400() throws Exception {
        String at = t0.toString();
        mvc.perform(get("/api/predictions").param("from", at).param("to", at).session(operator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("to"));
        mvc.perform(get("/api/predictions").param("riskLevel", "CRITICAL").session(operator))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/predictions").param("from", "yesterday").session(operator))
                .andExpect(status().isBadRequest());
    }

    private void expectReadings(MockHttpServletRequestBuilder request, long... readingIds) throws Exception {
        var result = mvc.perform(request.session(operator)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(readingIds.length))
                .andExpect(jsonPath("$.items.length()").value(readingIds.length));
        for (int i = 0; i < readingIds.length; i++) {
            result.andExpect(jsonPath("$.items[" + i + "].readingId").value(readingIds[i]));
        }
    }

    /** Stores a reading and its prediction in one transaction; returns the reading id. */
    private long store(StorageSession session, long second, double temperature, RiskLevel level, AlertSource source,
                       Quality quality, String currentGap, String lastGap, boolean incomplete) {
        Instant at = t0.plusSeconds(second);
        String snapshot = String.format(Locale.ROOT, """
                {"features":{"temperatureC":%s,"humidityPct":70.0,"observedAboveUpperSeconds":%s,
                "elapsedStorageSeconds":%s,"productType":"RAW_MEAT","incompleteHistory":%s,
                "currentIssue":"%s","lastGapReason":"%s"},"thresholdId":null,"ruleCutoffs":null}""",
                temperature, (double) second, (double) second, incomplete, currentGap, lastGap);
        return new TransactionTemplate(transactionManager).execute(status -> {
            long id = readings.insert(session.deviceId(), session.id(), at, at, temperature, 70.0, null).id();
            predictions.insert(id, new Estimate(level, source, MODEL, quality), at, snapshot);
            return id;
        });
    }

    private MockHttpSession login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username).param("password", PASSWORD).session(session).with(csrf()))
                .andExpect(status().isOk());
        return session;
    }
}
