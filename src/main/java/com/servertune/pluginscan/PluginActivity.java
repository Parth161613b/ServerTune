package com.servertune.pluginscan;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, allocation-free counters for one plugin during one diagnostic window.
 *
 * <p>This is the hot path. An instance is touched once per wrapped event handler call, which on
 * a busy server can mean thousands of times per second, so it does exactly four things: add a
 * duration, bump a count, keep a maximum, and nothing else. There is no list of samples, no
 * timestamp history and no per-event breakdown, because any of those would grow without bound
 * over the window and would make the profiler's own cost scale with the activity it is trying
 * to measure.
 *
 * <p><b>Sync and async are kept apart.</b> Main-thread cost is what moves MSPT; async cost is
 * real work but does not delay the tick. Summing them would let a plugin doing heavy async I/O
 * outrank one stalling the tick, which is precisely backwards for this diagnostic. The report
 * shows them in separate columns and ranks on the sync figure alone.
 *
 * <p>Longs are atomic because async event handlers run off the main thread. {@code maxNanos}
 * uses a CAS loop rather than a lock: contention is rare (it only spins when a new maximum is
 * actually observed) and a lock on this path would be the profiler distorting the measurement.
 */
public final class PluginActivity {

    private final String pluginName;

    private final AtomicLong syncNanos = new AtomicLong();
    private final AtomicLong syncCalls = new AtomicLong();
    private final AtomicLong syncMaxNanos = new AtomicLong();

    private final AtomicLong asyncNanos = new AtomicLong();
    private final AtomicLong asyncCalls = new AtomicLong();

    /** Scheduled tasks the plugin owned when the window opened. Inventory, not a measurement. */
    private volatile int syncTaskCount;
    private volatile int asyncTaskCount;

    public PluginActivity(String pluginName) {
        this.pluginName = pluginName;
    }

    /**
     * Records one handler call. {@code mainThread} decides which bucket it lands in, and is
     * passed by the caller rather than derived here so this class stays Bukkit-free.
     */
    public void record(long nanos, boolean mainThread) {
        if (nanos < 0L) {
            return;
        }

        if (mainThread) {
            syncNanos.addAndGet(nanos);
            syncCalls.incrementAndGet();
            updateMax(nanos);
        } else {
            asyncNanos.addAndGet(nanos);
            asyncCalls.incrementAndGet();
        }
    }

    /** CAS loop: only spins when a genuinely larger value arrives, which is rare. */
    private void updateMax(long nanos) {
        long current = syncMaxNanos.get();
        while (nanos > current && !syncMaxNanos.compareAndSet(current, nanos)) {
            current = syncMaxNanos.get();
        }
    }

    public void setTaskCounts(int sync, int async) {
        this.syncTaskCount = Math.max(0, sync);
        this.asyncTaskCount = Math.max(0, async);
    }

    public String pluginName() {
        return pluginName;
    }

    public long syncCalls() {
        return syncCalls.get();
    }

    public long asyncCalls() {
        return asyncCalls.get();
    }

    public double syncMillis() {
        return syncNanos.get() / 1_000_000.0;
    }

    public double asyncMillis() {
        return asyncNanos.get() / 1_000_000.0;
    }

    public double peakSyncMillis() {
        return syncMaxNanos.get() / 1_000_000.0;
    }

    public double averageSyncMillis() {
        long calls = syncCalls.get();
        return calls == 0L ? 0.0 : syncMillis() / calls;
    }

    public int syncTaskCount() {
        return syncTaskCount;
    }

    public int asyncTaskCount() {
        return asyncTaskCount;
    }

    public int totalTaskCount() {
        return syncTaskCount + asyncTaskCount;
    }

    /** True when nothing at all was observed - no timed calls and no owned tasks. */
    public boolean isEmpty() {
        return syncCalls.get() == 0L && asyncCalls.get() == 0L && totalTaskCount() == 0;
    }

    /** Immutable copy for ranking and reporting, so the live counters can be discarded. */
    public Measurement snapshot() {
        return new Measurement(pluginName, syncMillis(), averageSyncMillis(), peakSyncMillis(),
                syncCalls(), asyncMillis(), asyncCalls(), syncTaskCount, asyncTaskCount);
    }

    /**
     * One plugin's observations, frozen.
     *
     * <p>Every field is something that was actually counted. There is no "estimated CPU" or
     * "percent of tick" field, because neither can be measured through the public Paper API -
     * see {@link PluginScanRanker} for what is and is not claimed.
     */
    public record Measurement(String pluginName,
                              double syncMillis,
                              double averageSyncMillis,
                              double peakSyncMillis,
                              long syncCalls,
                              double asyncMillis,
                              long asyncCalls,
                              int syncTaskCount,
                              int asyncTaskCount) {

        public boolean hasTimedEvidence() {
            return syncCalls > 0L;
        }

        public int totalTaskCount() {
            return syncTaskCount + asyncTaskCount;
        }
    }
}
