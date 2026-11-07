package com.icu.smart_cold_storage_monitor.monitoring;

import static com.icu.smart_cold_storage_monitor.monitoring.MonitoringModels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads completed JSONL records; incomplete or invalid records never become trusted measurements. */
@Repository
public class JsonlSensorSource implements SensorSource {
    private final MonitoringProperties properties;
    private final ObjectMapper mapper;
    public JsonlSensorSource(MonitoringProperties properties, ObjectMapper mapper) {
        this.properties=properties; this.mapper=mapper;
    }
    public SensorSnapshot load() {
        properties.validate();
        List<Reading> result=new ArrayList<>(); List<String> issues=new ArrayList<>(); int rejected=0;
        var path=properties.getSensorPath();
        try {
            if (!Files.isRegularFile(path) || Files.size(path)>properties.getMaxSensorFileBytes())
                return new SensorSnapshot(List.of(),0,List.of("SENSOR_SOURCE_UNAVAILABLE_OR_TOO_LARGE"));
            Set<String> identities=new HashSet<>();
            // Read a bounded snapshot. A partially appended last record is rejected until the next request.
            byte[] bytes;
            try (var input=Files.newInputStream(path)) { bytes=input.readNBytes((int)Math.min(Integer.MAX_VALUE,properties.getMaxSensorFileBytes()+1)); }
            if (bytes.length>properties.getMaxSensorFileBytes())
                return new SensorSnapshot(List.of(),0,List.of("SENSOR_SOURCE_UNAVAILABLE_OR_TOO_LARGE"));
            for (String line:new String(bytes,StandardCharsets.UTF_8).split("\\R")) {
                if(line.isBlank()) continue;
                try {
                    JsonNode n=mapper.readTree(line);
                    String device=text(n,"deviceId"),boot=text(n,"device_session_id");
                    JsonNode seq=n.get("sequence_number");
                    if(seq==null || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.asLong()<0)
                        throw new IllegalArgumentException("Invalid sequence.");
                    Instant measured=Instant.parse(text(n,"measured_at"));
                    String status=text(n,"clock_sync_status"),source=text(n,"time_source");
                    if(!Set.of("SYNCED","HOLDOVER","UNSYNCED","SIMULATED_ALIGNED").contains(status))
                        throw new IllegalArgumentException("Unknown clock status.");
                    JsonNode synthetic=n.get("synthetic");
                    if(synthetic==null || !synthetic.isBoolean()) throw new IllegalArgumentException("Missing provenance.");
                    boolean simulated=synthetic.asBoolean();
                    if(status.equals("SIMULATED_ALIGNED") && !simulated) throw new IllegalArgumentException("Invalid provenance.");
                    double[] decoded=Sht45Codec.decode(text(n,"frame_hex"));
                    for(int i=0;i<2;i++) {
                        JsonNode value=n.get(i==0?"temperatureC":"humidityPct");
                        if(value==null || !value.isNumber() || !Double.isFinite(value.asDouble())
                            || Math.abs(value.asDouble()-decoded[i])>0.01)
                            throw new IllegalArgumentException("Frame/value mismatch.");
                    }
                    Instant received=n.hasNonNull("received_at")?Instant.parse(n.get("received_at").asString()):null;
                    String identity=device+"/"+boot+"/"+seq.asLong();
                    if(!identities.add(identity)) { rejected++;continue; }
                    result.add(new Reading(device,boot,seq.asLong(),measured,received,decoded[0],decoded[1],simulated,status,source));
                } catch (RuntimeException ex) { rejected++; }
            }
            if(rejected>0) issues.add("INVALID_OR_DUPLICATE_SENSOR_RECORDS");
        } catch (java.io.IOException ex) { issues.add("SENSOR_SOURCE_UNAVAILABLE"); }
        return new SensorSnapshot(List.copyOf(result),rejected,List.copyOf(issues));
    }
    private static String text(JsonNode n,String key) {
        JsonNode v=n.get(key);
        if(v==null || !v.isString() || v.asString().isBlank()) throw new IllegalArgumentException("Missing "+key);
        return v.asString();
    }
}
