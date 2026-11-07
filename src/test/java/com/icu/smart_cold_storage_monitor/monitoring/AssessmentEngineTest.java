package com.icu.smart_cold_storage_monitor.monitoring;
import static org.assertj.core.api.Assertions.*;
import static com.icu.smart_cold_storage_monitor.monitoring.MonitoringModels.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AssessmentEngineTest {
    final Instant now=Instant.parse("2026-11-05T17:10:00Z");
    final MonitoringProperties p=new MonitoringProperties();
    final AssessmentEngine engine=new AssessmentEngine();
    Lot lot(Double min,Double max,Double rhMin,Double rhMax,String quality,Instant expiry) {
        return new Lot(1,"LOT","SKU","Product","pack",10,"ZONE",quality,now.minusSeconds(600),expiry,min,max,rhMin,rhMax,"reference","https://example.org");
    }
    Reading reading(double t,double rh,Instant at,boolean synthetic,String clock) {
        return new Reading("DEV","BOOT",1,at,null,t,rh,synthetic,clock,"TEST");
    }
    @Test void inclusiveBoundsAreAcceptable() {
        for(double t:new double[]{2,8}) assertThat(engine.assess(lot(2.,8.,60.,80.,"AVAILABLE",now.plusSeconds(172800)),"DEV",reading(t,80,now,false,"SYNCED"),now,p,false).status()).isEqualTo(Status.OK);
    }
    @Test void temperatureAndHumidityViolationsAreIndependent() {
        var a=engine.assess(lot(2.,8.,60.,80.,"AVAILABLE",now.plusSeconds(172800)),"DEV",reading(9,85,now,false,"SYNCED"),now,p,false);
        assertThat(a.status()).isEqualTo(Status.ACTION_REQUIRED);
        assertThat(a.findings()).extracting(Finding::code).contains("TEMPERATURE_OUT_OF_RANGE","HUMIDITY_OUT_OF_RANGE");
    }
    @Test void missingLowerBoundIsNotTreatedAsZero() {
        var a=engine.assess(lot(null,-18.,null,null,"AVAILABLE",now.plusSeconds(172800)),"DEV",reading(-22,70,now,false,"SYNCED"),now,p,false);
        assertThat(a.environmentStatus()).isEqualTo(Status.OK);
        assertThat(a.humiditySpecified()).isFalse();
        assertThat(a.findings()).extracting(Finding::code).doesNotContain("TEMPERATURE_OUT_OF_RANGE");
    }
    @Test void unmappedAndStaleConditionsNeverPass() {
        var l=lot(2.,8.,60.,80.,"AVAILABLE",now.plusSeconds(172800));
        assertThat(engine.assess(l,null,null,now,p,false).environmentStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(engine.assess(l,"DEV",reading(4,70,now.minusSeconds(31),false,"SYNCED"),now,p,false).environmentStatus()).isEqualTo(Status.UNKNOWN);
    }
    @Test void liveRejectsSyntheticAndUnsyncedButReplayAllowsSynthetic() {
        var l=lot(2.,8.,60.,80.,"AVAILABLE",now.plusSeconds(172800));
        var sim=reading(4,70,now,true,"SIMULATED_ALIGNED");
        assertThat(engine.assess(l,"DEV",sim,now,p,false).environmentStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(engine.assess(l,"DEV",sim,now,p,true).environmentStatus()).isEqualTo(Status.OK);
        assertThat(engine.assess(l,"DEV",reading(4,70,now,false,"UNSYNCED"),now,p,true).environmentStatus()).isEqualTo(Status.UNKNOWN);
    }
    @Test void expiredAndHeldLotRemainsActionableEvenWithoutSensors() {
        var a=engine.assess(lot(2.,8.,60.,80.,"HOLD",now),null,null,now,p,false);
        assertThat(a.status()).isEqualTo(Status.ACTION_REQUIRED);
        assertThat(a.environmentStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(a.findings()).extracting(Finding::code).contains("QUALITY_HOLD","USE_BY_REACHED","NO_DEVICE_MAPPING");
    }
    @Test void readingBeforeLotReceiptIsNotUsed() {
        var l=lot(2.,8.,60.,80.,"AVAILABLE",now.plusSeconds(172800));
        assertThat(engine.assess(l,"DEV",reading(4,70,now.minusSeconds(601),false,"SYNCED"),now,p,true).findings()).extracting(Finding::code).contains("READING_BEFORE_RECEIPT");
    }
    @Test void holdoverIsVisibleAndNotAnUnqualifiedPass() {
        var l=lot(2.,8.,60.,80.,"AVAILABLE",now.plusSeconds(172800));
        assertThat(engine.assess(l,"DEV",reading(4,70,now,false,"HOLDOVER"),now,p,false).status()).isEqualTo(Status.WARNING);
    }
}
