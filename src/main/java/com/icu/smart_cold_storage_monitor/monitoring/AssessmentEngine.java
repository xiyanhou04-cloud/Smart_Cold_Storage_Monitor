package com.icu.smart_cold_storage_monitor.monitoring;

import static com.icu.smart_cold_storage_monitor.monitoring.MonitoringModels.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Versioned condition checks, not biological spoilage predictions or food safety certification. */
public class AssessmentEngine {
    public static final String VERSION="conditions-v1";
    public Assessment assess(Lot lot,String device,Reading reading,Instant now,MonitoringProperties p,boolean replay) {
        List<Finding> f=new ArrayList<>();
        Status environment=Status.OK;
        boolean humidity=lot.rhMin()!=null || lot.rhMax()!=null;
        boolean temperature=lot.tempMin()!=null || lot.tempMax()!=null;
        if ("HOLD".equals(lot.qualityStatus())) add(f,"QUALITY_HOLD",Status.ACTION_REQUIRED,"Lot is on quality hold.");
        if (!now.isBefore(lot.useBy())) add(f,"USE_BY_REACHED",Status.ACTION_REQUIRED,"Configured lot use-by time has been reached.");
        else if(!now.plus(p.getExpiringSoon()).isBefore(lot.useBy())) add(f,"EXPIRING_SOON",Status.WARNING,"Lot approaches its configured use-by time.");
        if(device==null) {environment=Status.UNKNOWN;add(f,"NO_DEVICE_MAPPING",Status.UNKNOWN,"No device is assigned to this location.");}
        else if(reading==null) {environment=Status.UNKNOWN;add(f,"NO_READING",Status.UNKNOWN,"No valid reading exists at or before the evaluation time.");}
        else if(reading.measuredAt().isBefore(lot.receivedAt())) {environment=Status.UNKNOWN;add(f,"READING_BEFORE_RECEIPT",Status.UNKNOWN,"Reading predates this lot's receipt.");}
        else if(reading.measuredAt().isAfter(now) || reading.measuredAt().plus(p.getStaleAfter()).isBefore(now)) {
            environment=Status.UNKNOWN;add(f,"STALE_OR_FUTURE_READING",Status.UNKNOWN,"Reading is not current for this evaluation time.");
        } else if(reading.clockStatus().equals("UNSYNCED") || (!replay && (reading.synthetic() || reading.clockStatus().equals("SIMULATED_ALIGNED")))) {
            environment=Status.UNKNOWN;add(f,"UNTRUSTED_TIME_OR_SIMULATION",Status.UNKNOWN,"Live assessment requires a synchronized, non-synthetic acquisition time.");
        } else {
            if(reading.clockStatus().equals("HOLDOVER")) {environment=Status.WARNING;add(f,"CLOCK_HOLDOVER",Status.WARNING,"Clock is running without current synchronization.");}
            if(temperature && outside(reading.temperatureC(),lot.tempMin(),lot.tempMax())) {
                environment=Status.ACTION_REQUIRED;add(f,"TEMPERATURE_OUT_OF_RANGE",Status.ACTION_REQUIRED,"Temperature violates the product's specified limits.");
            }
            if(humidity && outside(reading.humidityPct(),lot.rhMin(),lot.rhMax())) {
                environment=Status.ACTION_REQUIRED;add(f,"HUMIDITY_OUT_OF_RANGE",Status.ACTION_REQUIRED,"Humidity violates the product's specified limits.");
            }
            if(!temperature) {environment=combine(environment,Status.UNKNOWN);add(f,"TEMPERATURE_UNSPECIFIED",Status.UNKNOWN,"No product temperature requirement is specified.");}
            if(!humidity) add(f,"HUMIDITY_UNSPECIFIED",Status.WARNING,"Humidity was not assessed because no requirement is specified.");
            if(reading.synthetic()) add(f,"SIMULATED_REPLAY",Status.WARNING,"Assessment uses synthetic replay data.");
        }
        Status overall=environment;
        for(Finding finding:f) overall=combine(overall,finding.severity());
        return new Assessment(lot,device,reading,overall,environment,humidity,temperature,List.copyOf(f));
    }
    private static boolean outside(double v,Double min,Double max) {return min!=null && v<min || max!=null && v>max;}
    private static void add(List<Finding> f,String code,Status s,String message) {f.add(new Finding(code,s,message));}
    private static Status combine(Status a,Status b) {
        if(a==Status.ACTION_REQUIRED || b==Status.ACTION_REQUIRED) return Status.ACTION_REQUIRED;
        if(a==Status.UNKNOWN || b==Status.UNKNOWN) return Status.UNKNOWN;
        if(a==Status.WARNING || b==Status.WARNING) return Status.WARNING;
        return Status.OK;
    }
}
