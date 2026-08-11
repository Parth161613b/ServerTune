package com.servertune.selfmonitor;

/**
 * Wall-clock timer for one subsystem execution.
 *
 * <p>{@code System.nanoTime()} is taken at construction, which happens inside the scheduled task
 * body, so no scheduler or queue delay is included - this measures the work between the two calls
 * and nothing else. It is elapsed wall-clock, not CPU time: a GC pause or an OS preemption landing
 * inside the block is counted, which is deliberate, because from the server tick's point of view
 * that time really was spent.
 *
 * <p><b>Nested timers.</b> {@code health-monitor} wraps {@code metrics-collection}. Without
 * subtracting the inner span, the inner cost was charged to both names: the two figures moved
 * together within a fraction of a millisecond and the outer one could never come in under budget
 * however cheap its own work was. {@link #addNestedNanos} lets an inner timer discount itself from
 * whatever timer encloses it, so each name is charged only its own exclusive cost.
 *
 * <p>Not thread-safe, and does not need to be: an instance is created, used and dropped inside one
 * synchronous block on one thread.
 */
public class ExecutionTimer {

    private final SelfPerformanceMonitor monitor;
    private final String subsystemName;
    private final long startTimeNanos;
    private final ExecutionTimer parent;

    /** Time attributed to timers nested inside this one, subtracted at {@link #stop()}. */
    private long nestedNanos;
    private boolean stopped;

    ExecutionTimer(SelfPerformanceMonitor monitor, String subsystemName, ExecutionTimer parent) {
        this.monitor = monitor;
        this.subsystemName = subsystemName;
        this.parent = parent;
        this.startTimeNanos = System.nanoTime();
    }

    public ExecutionTimer(SelfPerformanceMonitor monitor, String subsystemName) {
        this(monitor, subsystemName, null);
    }

    /** Discounts an inner timer's span from this one, so nested work is not double-charged. */
    void addNestedNanos(long nanos) {
        this.nestedNanos += nanos;
    }

    public void stop() {
        // Guard against a double stop: the timers live in try/finally blocks, and charging the same
        // span twice would inflate the very number this class exists to report accurately.
        if (stopped) {
            return;
        }
        stopped = true;

        long elapsedNanos = System.nanoTime() - startTimeNanos;
        monitor.finishTimer(this);

        if (parent != null) {
            parent.addNestedNanos(elapsedNanos);
        }

        // Exclusive cost: this block's elapsed time less anything a nested timer already owns.
        // Clamped at zero because the two clock reads bracketing a nested timer are not the same
        // reads it made, so tiny negative residues are possible.
        long exclusiveNanos = Math.max(0L, elapsedNanos - nestedNanos);
        monitor.recordExecution(subsystemName, exclusiveNanos / 1_000_000.0);
    }

    public String getSubsystemName() {
        return subsystemName;
    }

    /** The timer this one is nested inside, or null at the outermost level. */
    ExecutionTimer getParent() {
        return parent;
    }
}
