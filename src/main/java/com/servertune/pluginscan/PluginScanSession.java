package com.servertune.pluginscan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * One diagnostic window's state and lifecycle. Bukkit-free, so timeout, cancellation and the
 * terminal-state rules are unit tested without a running server.
 *
 * <p><b>The state machine is one-way.</b> A session is RUNNING exactly once and then reaches
 * exactly one terminal state, whichever arrives first: the window expiring, an administrator
 * cancelling, or the performance guard aborting it. Every transition returns false if the
 * session already finished, so a cancel that races the timeout cannot produce two reports or
 * tear the profiler down twice.
 *
 * <p>The clock is injected as a {@link LongSupplier} of nanoseconds rather than read from
 * {@code System.nanoTime()} directly, so a test can expire a 10-second window instantly instead
 * of sleeping through it. That is the only reason the seam exists.
 */
public final class PluginScanSession {

    /**
     * The MSPT above which the window counts as elevated. Inherited from the existing ladder
     * ({@code performance-guard.states.warning.mspt} / {@code diagnostics.thresholds.mspt
     * .warning}, both 55.0) rather than invented here, so a scan and a guard state never
     * disagree about the same server. 50 ms is exactly one tick, so a lower figure would call a
     * healthy 20 TPS server elevated.
     */
    static final double ELEVATED_MSPT = 55.0;

    public enum State { RUNNING, COMPLETED, CANCELLED, ABORTED_CRITICAL }

    private final PluginScanSettings settings;
    private final int durationSeconds;
    private final LongSupplier nanoClock;
    private final long startNanos;

    /** Concurrent because async handlers record into it off the main thread. */
    private final Map<String, PluginActivity> activities = new ConcurrentHashMap<>();

    private final AtomicLong overheadNanos = new AtomicLong();

    private volatile State state = State.RUNNING;

    private double tpsSum;
    private double msptSum;
    private int samples;
    private double peakMspt;
    private int onlinePlayers;
    private int loadedChunks;
    private int loadedEntities;
    private int pluginsInstalled;
    private int pluginsEnabled;
    private int instrumentedEventCount;

    public PluginScanSession(PluginScanSettings settings, int requestedSeconds,
                             LongSupplier nanoClock) {
        this.settings = settings == null ? PluginScanSettings.defaults() : settings;
        this.durationSeconds = this.settings.resolveDuration(requestedSeconds);
        this.nanoClock = nanoClock == null ? System::nanoTime : nanoClock;
        this.startNanos = this.nanoClock.getAsLong();
    }

    /** The clamped window this session will actually run for. */
    public int durationSeconds() {
        return durationSeconds;
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public double elapsedSeconds() {
        return (nanoClock.getAsLong() - startNanos) / 1_000_000_000.0;
    }

    /** Whole elapsed seconds, for the "Elapsed: 5 / 10 seconds" progress line. */
    public int elapsedWholeSeconds() {
        return (int) elapsedSeconds();
    }

    /**
     * True once the window is up. Checked by the runner rather than trusted to a delayed task
     * alone, so a stalled or rescheduled tick cannot extend the session past its ceiling.
     */
    public boolean isExpired() {
        return elapsedSeconds() >= durationSeconds;
    }

    /**
     * Returns the accumulator for a plugin, creating it on first sight.
     *
     * <p>Called when a handler is wrapped, not per event - the wrapper holds the reference it is
     * given, so the hot path never touches this map.
     */
    public PluginActivity activityFor(String pluginName) {
        return activities.computeIfAbsent(pluginName, PluginActivity::new);
    }

    /** Records one measurement. Convenience for tests and for non-wrapped evidence sources. */
    public void record(String pluginName, long nanos, boolean mainThread) {
        activityFor(pluginName).record(nanos, mainThread);
    }

    /** ServerTune's own cost while profiling, so the report can admit to it (§23). */
    public void addOverheadNanos(long nanos) {
        if (nanos > 0L) {
            overheadNanos.addAndGet(nanos);
        }
    }

    public double overheadMillis() {
        return overheadNanos.get() / 1_000_000.0;
    }

    /**
     * Folds in one health sample. Called at the progress interval from data the existing
     * snapshot cache already produced - this class starts no monitoring of its own.
     */
    public void recordSample(double tps, double mspt, int players, int chunks, int entities) {
        tpsSum += tps;
        msptSum += mspt;
        samples++;
        if (mspt > peakMspt) {
            peakMspt = mspt;
        }
        this.onlinePlayers = players;
        this.loadedChunks = chunks;
        this.loadedEntities = entities;
    }

    /** Plugin inventory, collected once at session start (§9). */
    public void setInventory(int installed, int enabled, int instrumentedEvents) {
        this.pluginsInstalled = installed;
        this.pluginsEnabled = enabled;
        this.instrumentedEventCount = instrumentedEvents;
    }

    public double averageTps() {
        return samples == 0 ? 20.0 : tpsSum / samples;
    }

    public double averageMspt() {
        return samples == 0 ? 0.0 : msptSum / samples;
    }

    public double peakMspt() {
        return peakMspt;
    }

    /** Whether the window's average MSPT was elevated. Sharpens wording; never creates findings. */
    public boolean msptElevated() {
        return averageMspt() >= ELEVATED_MSPT;
    }

    public boolean complete() {
        return finish(State.COMPLETED);
    }

    public boolean cancel() {
        return finish(State.CANCELLED);
    }

    public boolean abortCritical() {
        return finish(State.ABORTED_CRITICAL);
    }

    /** Synchronized so a cancel racing the timeout produces exactly one terminal transition. */
    private synchronized boolean finish(State terminal) {
        if (state != State.RUNNING) {
            return false;
        }
        state = terminal;
        return true;
    }

    /**
     * Builds the report. Safe in any state and after {@link #clear()}: a cancelled session
     * reports what it managed to observe, which is honest, and an empty one reports nothing.
     */
    public PluginScanReport buildReport(PluginScanRanker ranker) {
        List<PluginActivity.Measurement> measurements = new ArrayList<>(activities.size());
        for (PluginActivity activity : activities.values()) {
            if (!activity.isEmpty()) {
                measurements.add(activity.snapshot());
            }
        }

        PluginScanRanker effective = ranker == null ? new PluginScanRanker(settings) : ranker;
        List<PluginFinding> findings = effective.rank(measurements, msptElevated());

        return PluginScanReport.builder(outcome())
                .elapsedSeconds(elapsedSeconds())
                .requestedSeconds(durationSeconds)
                .tick(averageTps(), averageMspt(), peakMspt)
                .world(onlinePlayers, loadedChunks, loadedEntities)
                .plugins(pluginsInstalled, pluginsEnabled)
                .instrumentedEventCount(instrumentedEventCount)
                .measuredPluginCount(measurements.size())
                .overheadMillis(overheadMillis())
                .findings(findings)
                .build();
    }

    private PluginScanReport.Outcome outcome() {
        return switch (state) {
            case CANCELLED -> PluginScanReport.Outcome.CANCELLED;
            case ABORTED_CRITICAL -> PluginScanReport.Outcome.ABORTED_CRITICAL;
            default -> PluginScanReport.Outcome.COMPLETED;
        };
    }

    /**
     * Drops every accumulator and plugin reference (§18). Called after the report is built, so
     * nothing observed during the window is still reachable from the service afterwards.
     */
    public void clear() {
        activities.clear();
    }

    /** Visible for tests asserting that cleanup actually released the references. */
    public int trackedPluginCount() {
        return activities.size();
    }
}
