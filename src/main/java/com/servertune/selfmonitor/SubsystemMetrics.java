package com.servertune.selfmonitor;

/**
 * Timing for one subsystem: last, max, exponential moving average, and a count.
 *
 * <p>Writes happen under {@code synchronized}; reads do not, because they come from command
 * handlers and reporting paths on a different thread from the writer. The four mutable fields are
 * therefore {@code volatile}: without it a reader had no guarantee of ever seeing a write, and
 * {@code /optimizer debug} could print a stale figure - or, for the {@code long} count, a torn one,
 * since a non-volatile long has no atomic-read guarantee on 32-bit JVMs.
 *
 * <p>{@code volatile} on four fields written once per subsystem execution costs a store barrier on
 * an already-synchronized path, not a new lock, and adds nothing at all to the read side.
 */
public class SubsystemMetrics {

    private final String subsystemName;
    private volatile double averageExecutionMs;
    private volatile double maxExecutionMs;
    private volatile double recentExecutionMs;
    private volatile long executionCount;

    public SubsystemMetrics(String subsystemName) {
        this.subsystemName = subsystemName;
        this.averageExecutionMs = 0.0;
        this.maxExecutionMs = 0.0;
        this.recentExecutionMs = 0.0;
        this.executionCount = 0;
    }

    public synchronized void recordExecution(double executionTimeMs) {
        // Reads of executionCount and averageExecutionMs below are safe unsynchronized-looking
        // accesses only because every write to them is inside this method, and this method holds
        // the monitor. Do not move the EMA update outside it.
        executionCount++;
        recentExecutionMs = executionTimeMs;

        // Update max
        if (executionTimeMs > maxExecutionMs) {
            maxExecutionMs = executionTimeMs;
        }

        // Update running average (weighted towards recent values)
        if (executionCount == 1) {
            averageExecutionMs = executionTimeMs;
        } else {
            // Exponential moving average with alpha = 0.3
            averageExecutionMs = (0.3 * executionTimeMs) + (0.7 * averageExecutionMs);
        }
    }

    public String getSubsystemName() {
        return subsystemName;
    }

    public double getAverageExecutionMs() {
        return averageExecutionMs;
    }

    public double getMaxExecutionMs() {
        return maxExecutionMs;
    }

    public double getRecentExecutionMs() {
        return recentExecutionMs;
    }

    public long getExecutionCount() {
        return executionCount;
    }
}
