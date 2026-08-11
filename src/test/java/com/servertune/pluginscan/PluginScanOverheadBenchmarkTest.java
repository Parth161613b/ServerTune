package com.servertune.pluginscan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 23: measure the diagnostic's own overhead rather than asserting it is small.
 *
 * <h2>What this can and cannot measure</h2>
 *
 * <p>The per-call wrapper does four things: read {@code System.nanoTime()} twice, ask Bukkit
 * whether this is the primary thread, and record into {@link PluginActivity}. Three of the four
 * are measured here. {@code Bukkit.isPrimaryThread()} is not - paper-api is {@code compileOnly},
 * so it cannot be called from a unit test, and faking it would produce a number that describes
 * the fake rather than the server.
 *
 * <p>So the figure below is a floor on the wrapper's cost, not the whole of it, and it is
 * reported that way. The remainder has to be measured on a live server; the procedure is in
 * {@code docs/PLUGIN-DIAGNOSTICS.md} and the plugin measures itself at runtime through
 * {@link PluginScanSession#addOverheadNanos}, which is what the report's overhead line prints.
 * Nothing here is extrapolated into a claim about a running Paper server.
 */
class PluginScanOverheadBenchmarkTest {

    private static final int CALLS = 2_000_000;
    private static final int PLUGINS = 40;

    /**
     * The hot path: one accumulate per wrapped handler call. This runs as many times per second
     * as the server fires the wrapped events, so it is the number that decides whether the
     * profiler distorts what it is profiling.
     */
    @Test
    void perCallAccumulationCostIsSubMicrosecond() {
        PluginActivity activity = new PluginActivity("Benchmark");

        for (int i = 0; i < 200_000; i++) {
            activity.record(i, true);
        }

        long startNanos = System.nanoTime();
        for (int i = 0; i < CALLS; i++) {
            long callStart = System.nanoTime();
            activity.record(System.nanoTime() - callStart, true);
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        double nanosPerCall = (double) elapsedNanos / CALLS;

        System.out.printf("Plugin scan wrapper (2x nanoTime + record): %,d calls in %.1f ms "
                        + "= %.1f ns/call%n", CALLS, elapsedNanos / 1_000_000.0, nanosPerCall);
        System.out.printf("  At 5,000 wrapped handler calls per tick that is %.3f ms of the "
                + "50 ms tick budget.%n", nanosPerCall * 5_000 / 1_000_000.0);
        System.out.println("  Excludes Bukkit.isPrimaryThread(), which cannot be called from a "
                + "unit test. Measure the total on a live server per docs/PLUGIN-DIAGNOSTICS.md.");

        // Deliberately loose. This is a regression guard against someone adding a map lookup or
        // an allocation to the hot path, not a performance claim - the printed figure is the
        // result, and it varies with the machine.
        assertTrue(nanosPerCall < 1_000.0,
                String.format("per-call cost was %.1f ns, expected well under 1000 ns",
                        nanosPerCall));
    }

    /** Contended accumulation, since async handlers write to the same counters. */
    @Test
    void concurrentAccumulationDoesNotCollapse() throws Exception {
        PluginActivity activity = new PluginActivity("Benchmark");
        int threads = Math.min(4, Runtime.getRuntime().availableProcessors());
        int perThread = 200_000;

        List<Thread> workers = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            workers.add(new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    activity.record(1_000L, false);
                }
            }));
        }

        long startNanos = System.nanoTime();
        workers.forEach(Thread::start);
        for (Thread worker : workers) {
            worker.join();
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        long total = (long) threads * perThread;
        System.out.printf("Plugin scan accumulator under %d-way contention: %,d records in "
                        + "%.1f ms = %.1f ns/record%n", threads, total,
                elapsedNanos / 1_000_000.0, (double) elapsedNanos / total);

        assertTrue(activity.asyncCalls() == total, "contended counters must not lose increments");
    }

    /**
     * The end-of-window cost. Ranking and rendering happen once, after profiling has already
     * stopped, so this is not on any hot path - it is measured to confirm that a server with a
     * large plugin list does not pay a visible spike when the report is produced.
     */
    @Test
    void reportGenerationIsWellUnderOneTick() {
        PluginScanSession session = new PluginScanSession(PluginScanSettings.defaults(), 30,
                System::nanoTime);
        for (int p = 0; p < PLUGINS; p++) {
            PluginActivity activity = session.activityFor("Plugin" + p);
            for (int i = 0; i < 500; i++) {
                activity.record(100_000L + i, true);
            }
            activity.setTaskCounts(p % 5, p % 3);
        }
        session.recordSample(19.1, 47.0, 30, 4_000, 12_000);
        session.complete();

        long startNanos = System.nanoTime();
        PluginScanReport report = session.buildReport(
                new PluginScanRanker(PluginScanSettings.defaults()));
        List<String> lines = PluginScanFormatter.render(report);
        long elapsedNanos = System.nanoTime() - startNanos;

        double elapsedMillis = elapsedNanos / 1_000_000.0;
        System.out.printf("Plugin scan report for %d measured plugins: %.3f ms (%d lines)%n",
                PLUGINS, elapsedMillis, lines.size());

        assertTrue(elapsedMillis < 25.0,
                String.format("report generation took %.3f ms, expected under 25 ms",
                        elapsedMillis));
        assertTrue(lines.size() > 10, "fixture should produce a populated report");
    }
}
