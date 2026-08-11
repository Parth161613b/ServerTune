package com.servertune.pluginscan;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 12 and Section 13: bounded aggregation, and async cost never mistaken for tick cost.
 *
 * <p>These tests exercise the real accumulator with real numbers. Nothing here pretends to
 * measure Paper - the counters are fed directly, which is exactly what the wrapped listener does
 * on a live server, so the arithmetic under test is the arithmetic that ships.
 */
class PluginActivityTest {

    private static final long MS = 1_000_000L;

    @Test
    void freshActivityIsEmpty() {
        assertTrue(new PluginActivity("Example").isEmpty());
    }

    @Test
    void syncCallsAccumulateIntoTheMainThreadBucket() {
        PluginActivity activity = new PluginActivity("Example");

        activity.record(2 * MS, true);
        activity.record(4 * MS, true);

        assertEquals(6.0, activity.syncMillis(), 1e-9);
        assertEquals(2L, activity.syncCalls());
        assertEquals(3.0, activity.averageSyncMillis(), 1e-9);
        assertEquals(0.0, activity.asyncMillis(), 1e-9);
        assertFalse(activity.isEmpty());
    }

    /**
     * Section 13. Async milliseconds must never leak into the sync total, or a plugin doing
     * background I/O would be reported as delaying the tick.
     */
    @Test
    void asyncCallsAreKeptEntirelySeparate() {
        PluginActivity activity = new PluginActivity("Example");

        activity.record(5 * MS, false);
        activity.record(50 * MS, false);

        assertEquals(0.0, activity.syncMillis(), 1e-9);
        assertEquals(0L, activity.syncCalls());
        assertEquals(55.0, activity.asyncMillis(), 1e-9);
        assertEquals(2L, activity.asyncCalls());
        assertFalse(activity.snapshot().hasTimedEvidence(),
                "async work alone is not timed evidence of main-thread cost");
    }

    @Test
    void peakTracksTheLargestSyncCallOnly() {
        PluginActivity activity = new PluginActivity("Example");

        activity.record(3 * MS, true);
        activity.record(9 * MS, true);
        activity.record(MS, true);
        activity.record(400 * MS, false);

        assertEquals(9.0, activity.peakSyncMillis(), 1e-9);
    }

    /** A clock that ran backwards must not corrupt a total. */
    @Test
    void negativeDurationsAreIgnored() {
        PluginActivity activity = new PluginActivity("Example");

        activity.record(-1_000L, true);
        activity.record(-1_000L, false);

        assertTrue(activity.isEmpty());
        assertEquals(0.0, activity.syncMillis(), 1e-9);
    }

    @Test
    void averageOfNoCallsIsZeroRatherThanNaN() {
        assertEquals(0.0, new PluginActivity("Example").averageSyncMillis(), 1e-9);
    }

    /** Task counts are inventory. They make a plugin non-empty but never a timed measurement. */
    @Test
    void taskCountsAreInventoryNotEvidence() {
        PluginActivity activity = new PluginActivity("Example");
        activity.setTaskCounts(3, 2);

        assertFalse(activity.isEmpty());
        assertEquals(5, activity.totalTaskCount());
        assertFalse(activity.snapshot().hasTimedEvidence());
        assertEquals(0.0, activity.syncMillis(), 1e-9);
    }

    @Test
    void negativeTaskCountsAreFloored() {
        PluginActivity activity = new PluginActivity("Example");
        activity.setTaskCounts(-4, -1);

        assertEquals(0, activity.totalTaskCount());
    }

    @Test
    void snapshotFreezesTheCountersAtTheMomentItWasTaken() {
        PluginActivity activity = new PluginActivity("Example");
        activity.record(2 * MS, true);

        PluginActivity.Measurement frozen = activity.snapshot();
        activity.record(98 * MS, true);

        assertEquals(2.0, frozen.syncMillis(), 1e-9,
                "a snapshot handed to the ranker must not keep changing underneath it");
        assertEquals(100.0, activity.syncMillis(), 1e-9);
    }

    /**
     * The accumulator is written to from async event handlers as well as the main thread. This
     * asserts the counters are actually atomic - a plain long here would lose increments.
     */
    @Test
    void concurrentRecordingLosesNothing() throws Exception {
        PluginActivity activity = new PluginActivity("Example");
        int threads = 4;
        int perThread = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        try {
            for (int t = 0; t < threads; t++) {
                boolean mainThread = t % 2 == 0;
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        activity.record(MS, mainThread);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2L * perThread, activity.syncCalls());
        assertEquals(2L * perThread, activity.asyncCalls());
        assertEquals(2.0 * perThread, activity.syncMillis(), 1e-6);
    }
}
