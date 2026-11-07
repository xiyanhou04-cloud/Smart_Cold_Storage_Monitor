package com.icu.smart_cold_storage_monitor.monitoring;
import static org.assertj.core.api.Assertions.*;
import static com.icu.smart_cold_storage_monitor.monitoring.MonitoringModels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MonitoringIntegrationTest {
    @TempDir Path temp;
    MonitoringProperties properties() {
        var p=new MonitoringProperties();p.getLocationDevices().put("RAW-MEAT","ESP32-SIM-001");return p;
    }
    @Test void readsAllFramesAndEvaluatesAllStockAtReplayTime() throws Exception {
        var p=properties();var hash=Files.readAllBytes(p.getInventoryPath());
        var inventory=new SqliteInventorySource(p);
        var sensors=new JsonlSensorSource(p,JsonMapper.builder().build());
        var readings=sensors.load().readings();
        assertThat(readings).hasSize(1805);
        assertThat(readings.stream().collect(java.util.stream.Collectors.groupingBy(Reading::measuredAt)))
            .hasSize(361).allSatisfy((time, samples)-> {
                assertThat(samples).hasSize(5);
                assertThat(samples).extracting(Reading::deviceId).doesNotHaveDuplicates();
            });
        assertThat(sensors.load().rejectedRecords()).isZero();
        var service=new AssessmentService(inventory,sensors,p,Clock.fixed(Instant.parse("2026-11-05T20:00:00Z"),ZoneOffset.UTC));
        var report=service.evaluate(Instant.parse("2026-11-05T17:10:00Z"));
        assertThat(report.lots()).hasSize(24);
        assertThat(report.lots().stream().filter(a->a.lot().location().equals("RAW-MEAT"))).allMatch(a->a.environmentStatus()==Status.ACTION_REQUIRED);
        assertThat(report.lots().stream().filter(a->!a.lot().location().equals("RAW-MEAT"))).allMatch(a->a.environmentStatus()==Status.UNKNOWN);
        assertThat(Files.readAllBytes(p.getInventoryPath())).isEqualTo(hash);
    }
    @Test void stockQueryExcludesMovementsThatHaveNotHappenedYet() {
        var p=properties();var repository=new SqliteInventorySource(p);
        assertThat(repository.loadStock(Instant.parse("2020-01-01T00:00:00Z"))).isEmpty();
        var early=repository.loadStock(Instant.parse("2026-11-05T11:00:00Z"));
        assertThat(early).hasSize(12);
        assertThat(early.stream().filter(l->l.id()==1).findFirst().orElseThrow().quantity()).isEqualTo(105);
    }
    @Test void corruptFramesDuplicatesAndPartialLinesAreRejected() throws Exception {
        var p=properties();
        String first=Files.readAllLines(p.getSensorPath()).getFirst();
        Path input=temp.resolve("sensor.jsonl");
        Files.writeString(input,first+"\n"+first+"\n"+first.replace("47 AF 74","47 AF 00")+"\n{partial");
        p.setSensorPath(input);
        var snapshot=new JsonlSensorSource(p,JsonMapper.builder().build()).load();
        assertThat(snapshot.readings()).hasSize(1);
        assertThat(snapshot.rejectedRecords()).isEqualTo(3);
    }
    @Test void missingSensorSourceKeepsAllInventoryVisible() {
        var p=properties();p.setSensorPath(temp.resolve("missing.jsonl"));
        var service=new AssessmentService(new SqliteInventorySource(p),new JsonlSensorSource(p,JsonMapper.builder().build()),p,Clock.fixed(Instant.parse("2026-11-05T17:10:00Z"),ZoneOffset.UTC));
        var report=service.evaluate(null);
        assertThat(report.lots()).hasSize(24);
        assertThat(report.sourceIssues()).isNotEmpty();
        assertThat(report.lots()).allMatch(a->a.environmentStatus()==Status.UNKNOWN);
    }
    @Test void latestReadingDoesNotLookAheadDuringReplay() {
        var p=properties();
        var service=new AssessmentService(new SqliteInventorySource(p),new JsonlSensorSource(p,JsonMapper.builder().build()),p,Clock.systemUTC());
        var at=Instant.parse("2026-11-05T17:05:02Z");
        assertThat(service.evaluate(at).lots().stream().filter(a->a.reading()!=null)).allMatch(a->a.reading().measuredAt().equals(Instant.parse("2026-11-05T17:05:00Z")));
    }
    @Test void badCrcCannotBeDecoded() {
        assertThatThrownBy(()->Sht45Codec.decode("47 AF 00 9B A3 79")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void appendedReadingsBecomeVisibleOnTheNextAssessment() throws Exception {
        var p=properties();var lines=Files.readAllLines(p.getSensorPath()).stream()
            .filter(line->line.contains("ESP32-SIM-001")).toList();
        Path input=temp.resolve("appended.jsonl");Files.writeString(input,lines.get(0)+"\n");
        p.setSensorPath(input);
        var service=new AssessmentService(new SqliteInventorySource(p),new JsonlSensorSource(p,JsonMapper.builder().build()),p,Clock.systemUTC());
        Instant at=Instant.parse("2026-11-05T17:10:00Z");
        assertThat(service.evaluate(at).lots().stream().filter(a->a.deviceId()!=null)).allMatch(a->a.environmentStatus()==Status.UNKNOWN);
        Files.writeString(input,lines.get(120)+"\n",StandardOpenOption.APPEND);
        assertThat(service.evaluate(at).lots().stream().filter(a->a.deviceId()!=null)).allMatch(a->a.environmentStatus()==Status.ACTION_REQUIRED);
    }
    @Test void missingInventoryNeverCreatesAnEmptyDatabase() {
        var p=properties();Path missing=temp.resolve("missing.sqlite");p.setInventoryPath(missing);
        assertThatThrownBy(()->new SqliteInventorySource(p).loadStock(Instant.now())).isInstanceOf(IllegalStateException.class);
        assertThat(missing).doesNotExist();
    }
}
