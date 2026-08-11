package com.servertune.pluginscan;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sections 18 through 22: one terminal state, a window that always ends, and nothing left behind.
 *
 * <p>The clock is injected, so a 30-second window expires in this test in no time at all. That
 * seam is the whole reason the session is Bukkit-free: a timeout test that actually slept for
 * thirty seconds would be too slow to run and too flaky to trust.
 */
class PluginScanSessionTest {

    private static final long SECOND = 1_000_000_000L;

    /** A hand-cranked nanosecond clock. Advances only when a test says so. */
    private static final class TestClock {
        private final AtomicLong nanos = new AtomicLong();

        long get() {
            return nanos.get();
        }

        void advanceSeconds(double seconds) {
            nanos.addAndGet((long) (seconds * SECOND));
        }
    }

    private final TestClock clock = new TestClock();

    private PluginScanSession session(int requestedSeconds) {
        return new PluginScanSession(PluginScanSettings.defaults(), requestedSeconds, clock::get);
    }

    @Test
    void aNewSessionIsRunningAndHasClampedItsWindow() {
        PluginScanSession session = session(0);

        assertTrue(session.isRunning());
        assertEquals(PluginScanSession.State.RUNNING, session.state());
        assertEquals(10, session.durationSeconds(), "an unspecified window uses the default");
    }

    /** Section 20. The window is clamped at construction, so nothing downstream can extend it. */
    @Test
    void anAbsurdRequestIsClampedBeforeTheSessionEverStarts() {
        PluginScanSession session = session(Integer.MAX_VALUE);

        assertTrue(session.durationSeconds() <= PluginScanSettings.HARD_MAX_DURATION_SECONDS);
        assertEquals(30, session.durationSeconds(), "the default config maximum");
    }

    @Test
    void expiryIsDrivenByElapsedTimeNotByBeingAsked() {
        PluginScanSession session = session(10);

        clock.advanceSeconds(9.5);
        assertFalse(session.isExpired());

        clock.advanceSeconds(0.5);
        assertTrue(session.isExpired());
    }

    @Test
    void elapsedWholeSecondsDrivesTheProgressLine() {
        PluginScanSession session = session(10);

        clock.advanceSeconds(5.7);

        assertEquals(5, session.elapsedWholeSeconds());
        assertEquals(5.7, session.elapsedSeconds(), 1e-6);
    }

    @Test
    void completingEndsTheSessionExactlyOnce() {
        PluginScanSession session = session(10);

        assertTrue(session.complete());
        assertFalse(session.complete(), "a second completion would produce a second report");
        assertEquals(PluginScanSession.State.COMPLETED, session.state());
        assertFalse(session.isRunning());
    }

    /** Section 19. Cancel is allowed at any point in the window. */
    @Test
    void cancellationStopsTheSessionMidWindow() {
        PluginScanSession session = session(30);
        clock.advanceSeconds(4);

        assertTrue(session.cancel());
        assertEquals(PluginScanSession.State.CANCELLED, session.state());
        assertFalse(session.isRunning());
    }

    @Test
    void cancellingAFinishedSessionIsRefusedRatherThanRepeated() {
        PluginScanSession session = session(10);
        session.complete();

        assertFalse(session.cancel());
        assertEquals(PluginScanSession.State.COMPLETED, session.state(),
                "a late cancel must not overwrite how the session actually ended");
    }

    /** Section 21. */
    @Test
    void criticalAbortIsItsOwnTerminalState() {
        PluginScanSession session = session(10);

        assertTrue(session.abortCritical());
        assertEquals(PluginScanSession.State.ABORTED_CRITICAL, session.state());
        assertEquals(PluginScanReport.Outcome.ABORTED_CRITICAL,
                session.buildReport(null).outcome());
    }

    /**
     * The race the synchronized transition exists for: an administrator typing cancel on the tick
     * the window expires. Exactly one caller may win, or the profiler is torn down twice.
     */
    @Test
    void concurrentTerminationsProduceExactlyOneWinner() throws Exception {
        PluginScanSession session = session(10);
        int threads = 8;
        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        try {
            for (int t = 0; t < threads; t++) {
                int which = t % 3;
                pool.submit(() -> {
                    start.await();
                    boolean won = switch (which) {
                        case 0 -> session.complete();
                        case 1 -> session.cancel();
                        default -> session.abortCritical();
                    };
                    if (won) {
                        winners.incrementAndGet();
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

        assertEquals(1, winners.get());
        assertFalse(session.isRunning());
    }

    @Test
    void samplesAverageAndPeakAcrossTheWindow() {
        PluginScanSession session = session(10);

        session.recordSample(20.0, 30.0, 4, 900, 2_100);
        session.recordSample(18.0, 70.0, 5, 950, 2_200);

        assertEquals(19.0, session.averageTps(), 1e-9);
        assertEquals(50.0, session.averageMspt(), 1e-9);
        assertEquals(70.0, session.peakMspt(), 1e-9);
    }

    @Test
    void anUnsampledWindowReportsAHealthyDefaultRatherThanZeroTps() {
        PluginScanSession session = session(10);

        assertEquals(20.0, session.averageTps(), 1e-9);
        assertEquals(0.0, session.averageMspt(), 1e-9);
        assertFalse(session.msptElevated());
    }

    @Test
    void elevationUsesTheExistingGuardThresholdRatherThanANewNumber() {
        PluginScanSession quiet = session(10);
        quiet.recordSample(20.0, PluginScanSession.ELEVATED_MSPT - 0.1, 1, 1, 1);
        assertFalse(quiet.msptElevated());

        PluginScanSession loaded = session(10);
        loaded.recordSample(14.0, PluginScanSession.ELEVATED_MSPT, 1, 1, 1);
        assertTrue(loaded.msptElevated());
    }

    @Test
    void recordedActivityReachesTheReport() {
        PluginScanSession session = session(10);
        session.record("Busy", 40_000_000L, true);
        session.record("Busy", 40_000_000L, true);
        for (int i = 0; i < 30; i++) {
            session.record("Busy", 1_000_000L, true);
        }
        clock.advanceSeconds(10);
        session.complete();

        PluginScanReport report = session.buildReport(
                new PluginScanRanker(PluginScanSettings.defaults()));

        assertEquals(PluginScanReport.Outcome.COMPLETED, report.outcome());
        assertEquals(1, report.measuredPluginCount());
        assertFalse(report.isEmpty());
        assertEquals("Busy", report.significantFindings().get(0).pluginName());
    }

    /** A plugin that did nothing must not appear as a measured plugin. */
    @Test
    void pluginsObservedDoingNothingAreNotCounted() {
        PluginScanSession session = session(10);
        session.activityFor("Idle");
        session.complete();

        PluginScanReport report = session.buildReport(null);

        assertEquals(0, report.measuredPluginCount());
        assertTrue(report.isEmpty());
    }

    /** Section 22. Nothing observed during the window stays reachable afterwards. */
    @Test
    void clearReleasesEveryPluginReference() {
        PluginScanSession session = session(10);
        session.record("A", 1_000_000L, true);
        session.record("B", 1_000_000L, false);
        assertEquals(2, session.trackedPluginCount());

        session.complete();
        session.clear();

        assertEquals(0, session.trackedPluginCount());
    }

    /**
     * The service builds the report before clearing, but a report built afterwards must still be
     * a valid empty report rather than an exception on a shutdown path.
     */
    @Test
    void reportAfterClearIsEmptyRatherThanBroken() {
        PluginScanSession session = session(10);
        session.record("A", 5_000_000L, true);
        session.cancel();
        session.clear();

        PluginScanReport report = session.buildReport(null);

        assertEquals(PluginScanReport.Outcome.CANCELLED, report.outcome());
        assertEquals(0, report.measuredPluginCount());
        assertTrue(report.isEmpty());
    }

    /** Section 23: the profiler's own cost is accumulated so the report can admit to it. */
    @Test
    void overheadAccumulatesAndIgnoresNonPositiveDurations() {
        PluginScanSession session = session(10);

        session.addOverheadNanos(2_000_000L);
        session.addOverheadNanos(3_000_000L);
        session.addOverheadNanos(-5_000_000L);
        session.addOverheadNanos(0L);

        assertEquals(5.0, session.overheadMillis(), 1e-9);
    }

    @Test
    void inventoryIsCarriedThroughToTheReport() {
        PluginScanSession session = session(10);
        session.setInventory(42, 40, 7);
        session.recordSample(19.5, 41.0, 8, 1_200, 3_000);
        session.complete();

        PluginScanReport report = session.buildReport(null);

        assertEquals(42, report.pluginsInstalled());
        assertEquals(40, report.pluginsEnabled());
        assertEquals(7, report.instrumentedEventCount());
        assertEquals(8, report.onlinePlayers());
        assertEquals(1_200, report.loadedChunks());
        assertEquals(3_000, report.loadedEntities());
    }

    /**
     * Section 22, in miniature: run, finish, run, finish, run, cancel, run, finish. Each session
     * is independent, so no counter or plugin reference survives into the next one.
     */
    @Test
    void repeatedSessionsAccumulateNothingAcrossRuns() {
        for (int run = 0; run < 4; run++) {
            PluginScanSession session = session(10);
            session.record("Example", 2_000_000L, true);
            assertEquals(1, session.trackedPluginCount());

            clock.advanceSeconds(10);
            if (run == 2) {
                assertTrue(session.cancel());
            } else {
                assertTrue(session.isExpired());
                assertTrue(session.complete());
            }

            PluginScanReport report = session.buildReport(null);
            assertEquals(1, report.measuredPluginCount(),
                    "run " + run + " must see only its own activity");

            session.clear();
            assertEquals(0, session.trackedPluginCount());
        }
    }
}
