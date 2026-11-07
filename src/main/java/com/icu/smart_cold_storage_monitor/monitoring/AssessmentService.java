package com.icu.smart_cold_storage_monitor.monitoring;

import static com.icu.smart_cold_storage_monitor.monitoring.MonitoringModels.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AssessmentService {
    private final InventorySource inventory;
    private final SensorSource sensors;
    private final MonitoringProperties properties;
    private final Clock clock;
    private final AssessmentEngine engine=new AssessmentEngine();
    public AssessmentService(InventorySource inventory,SensorSource sensors,MonitoringProperties properties,Clock clock) {
        this.inventory=inventory;this.sensors=sensors;this.properties=properties;this.clock=clock;
    }
    public Report evaluate(Instant replayAt) {
        properties.validate();
        Instant generated=clock.instant(),now=replayAt==null?generated:replayAt;
        boolean replay=replayAt!=null;
        List<Lot> lots=inventory.loadStock(now);
        SensorSnapshot snapshot=sensors.load();
        Map<String,Reading> latest=new HashMap<>();
        // Never use a future reading to evaluate the past. Preserve an UNSYNCED latest record as UNKNOWN.
        for(Reading reading:snapshot.readings()) {
            if(reading.measuredAt().isAfter(now)) continue;
            latest.merge(reading.deviceId(),reading,(a,b)->b.measuredAt().isAfter(a.measuredAt())?b:a);
        }
        List<Assessment> assessments=lots.stream().map(lot->{
            String device=properties.getLocationDevices().get(lot.location());
            return engine.assess(lot,device,device==null?null:latest.get(device),now,properties,replay);
        }).toList();
        return new Report(now,generated,replay?"REPLAY":"LIVE",AssessmentEngine.VERSION,
            "READ_ONLY_EXTERNAL_SQLITE","INDEPENDENT_JSONL",snapshot.issues(),snapshot.rejectedRecords(),assessments);
    }
}
