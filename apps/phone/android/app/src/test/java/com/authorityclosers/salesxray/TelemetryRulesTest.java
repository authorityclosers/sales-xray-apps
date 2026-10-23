package com.authorityclosers.salesxray;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Native telemetry as tiers and codes (1.0.25, owner 24 Oct 2026). */
public class TelemetryRulesTest {

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    public void ramAndStorageAreTiersNotNumbers() {
        // A Note 8 Pro reports about 5.6 GB of its 6 GB.
        assertEquals("6", TelemetryRules.ramTier((long) (5.6 * GB)));
        assertEquals("4", TelemetryRules.ramTier((long) (3.7 * GB)));
        assertEquals("2", TelemetryRules.ramTier((long) (1.8 * GB)));
        assertEquals("12plus", TelemetryRules.ramTier(11 * GB));
        assertEquals("lt1", TelemetryRules.storageTier(GB / 2));
        assertEquals("1to4", TelemetryRules.storageTier(3 * GB));
        assertEquals("16plus", TelemetryRules.storageTier(40 * GB));
    }

    @Test
    public void networkBandsAndGenerations() {
        assertEquals("unknown", TelemetryRules.downTier(0));
        assertEquals("lt1", TelemetryRules.downTier(600));
        assertEquals("5to20", TelemetryRules.downTier(12_000));
        assertEquals("20plus", TelemetryRules.downTier(90_000));
        assertEquals("4g", TelemetryRules.cellGeneration(13));
        assertEquals("5g", TelemetryRules.cellGeneration(20));
        assertEquals("3g", TelemetryRules.cellGeneration(15));
        assertEquals("unknown", TelemetryRules.cellGeneration(0));
    }

    @Test
    public void exitsAndStandbyBuckets() {
        assertEquals("anr", TelemetryRules.exitReason(6));
        assertEquals("crash", TelemetryRules.exitReason(4));
        assertEquals("low_memory", TelemetryRules.exitReason(3));
        assertEquals("killed_by_system", TelemetryRules.exitReason(2));
        assertEquals("other", TelemetryRules.exitReason(12));
        assertTrue(TelemetryRules.reportable("anr"));
        assertFalse(TelemetryRules.reportable(TelemetryRules.exitReason(10)));
        assertEquals("active", TelemetryRules.standbyBucket(10));
        assertEquals("restricted", TelemetryRules.standbyBucket(45));
        assertEquals("unknown", TelemetryRules.standbyBucket(99));
    }

    @Test
    public void deviceLabelsCarryNothingPersonal() {
        assertEquals("xiaomi", TelemetryRules.maker("Redmi"));
        assertEquals("samsung", TelemetryRules.maker("samsung"));
        assertEquals("unknown", TelemetryRules.maker(null));
        assertEquals("M1906G7G", TelemetryRules.modelLabel("M1906G7G"));
        assertEquals("Pixel 8 5G", TelemetryRules.modelLabel("Pixel 8 (5G)!"));
        assertEquals("141", TelemetryRules.major("141.0.7390.122"));
        assertEquals("unknown", TelemetryRules.major("dev-build"));
    }

    @Test
    public void framesCountSlowFrozenAndP90() {
        TelemetryRules.FrameStats stats = new TelemetryRules.FrameStats();
        for (int i = 0; i < 90; i++) stats.add(8_000_000L);
        for (int i = 0; i < 9; i++) stats.add(40_000_000L);
        stats.add(900_000_000L);
        assertEquals(100, stats.frames);
        assertEquals(10, stats.slow);
        assertEquals(1, stats.frozen);
        assertEquals(8, stats.p90());
        stats.add(40_000_000L);
        assertEquals(40, stats.p90());
        stats.clear();
        assertEquals(0, stats.frames);
        assertEquals(0, stats.p90());
    }
}
