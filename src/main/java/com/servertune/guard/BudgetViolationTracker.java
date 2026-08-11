package com.servertune.guard;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-protection bookkeeping: decides when one of the optimizer's own
 * subsystems is costing too much and should be warned about or suspended.
 *
 * <p>Backs the {@code self-monitoring.*} config keys. Bukkit-free;
 * {@link com.servertune.selfmonitor.SelfPerformanceMonitor}
 * owns one of these and performs the actual suspension.
 *
 * <p>Violations must be <b>consecutive</b>. A single slow pass - a GC pause landing inside
 * a timed block, a chunk-load storm during one cycle - is noise, and one compliant
 * execution clears the counter. Only a subsystem that is reliably over budget gets
 * suspended.
 *
 * <p>{@link #PROTECTED_SUBSYSTEMS} can never be suspended however badly they behave. If the
 * health monitor were suspended the guard would go blind and could never detect recovery,
 * so the fallback controller must stay alive under every condition.
 * Those subsystems still warn.
 */
public class BudgetViolationTracker {

    /** Suspending any of these would blind the guard, so they are warn-only. */
    public static final Set<String> PROTECTED_SUBSYSTEMS =
            Set.of("health-monitor", "metrics-collection", "fallback-controller", "performance-guard");

    /** What the caller should do about one recorded execution. */
    public enum Decision {
        /** Within budget, or budget checking is off. */
        OK,
        /** Over budget, but not yet often enough to act on. */
        WARN,
        /** Over budget for the configured number of consecutive executions - suspend it. */
        SUSPEND,
        /** Over budget repeatedly, but this subsystem must stay alive. Warn only. */
        WARN_PROTECTED
    }

    private final boolean enabled;
    private final boolean warnOnExceeded;
    private final boolean suspendOnExceeded;
    private final int violationsBeforeSuspend;
    private final Map<String, Double> budgets;
    private final double defaultBudgetMs;

    private final Map<String, AtomicInteger> consecutiveViolations = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> totalViolations = new ConcurrentHashMap<>();

    private BudgetViolationTracker(Builder b) {
        this.enabled = b.enabled;
        this.warnOnExceeded = b.warnOnExceeded;
        this.suspendOnExceeded = b.suspendOnExceeded;
        this.violationsBeforeSuspend = Math.max(1, b.violationsBeforeSuspend);
        this.budgets = Map.copyOf(b.budgets);
        this.defaultBudgetMs = b.defaultBudgetMs;
    }

    /**
     * Record one timed execution and decide what to do about it.
     *
     * @param subsystem     the timer name, e.g. {@code "health-monitor"} or a module name
     * @param executionMs   how long it took
     */
    public Decision record(String subsystem, double executionMs) {
        if (!enabled) {
            return Decision.OK;
        }

        double budget = budgetFor(subsystem);
        if (budget <= 0.0) {
            return Decision.OK;
        }

        if (executionMs <= budget) {
            // One good pass wipes the streak; only sustained overruns matter.
            AtomicInteger streak = consecutiveViolations.get(subsystem);
            if (streak != null) {
                streak.set(0);
            }
            return Decision.OK;
        }

        int streak = consecutiveViolations
                .computeIfAbsent(subsystem, k -> new AtomicInteger())
                .incrementAndGet();
        totalViolations.computeIfAbsent(subsystem, k -> new AtomicInteger()).incrementAndGet();

        if (streak < violationsBeforeSuspend) {
            return warnOnExceeded ? Decision.WARN : Decision.OK;
        }

        if (!suspendOnExceeded) {
            return warnOnExceeded ? Decision.WARN : Decision.OK;
        }

        if (PROTECTED_SUBSYSTEMS.contains(subsystem)) {
            return Decision.WARN_PROTECTED;
        }

        return Decision.SUSPEND;
    }

    /**
     * The budget for a subsystem. Explicit per-subsystem budgets win; anything not named
     * falls back to the shared optimization-module budget.
     */
    public double budgetFor(String subsystem) {
        return budgets.getOrDefault(subsystem, defaultBudgetMs);
    }

    /** Clears the streak after a suspension or resume so the module starts fresh. */
    public void clear(String subsystem) {
        AtomicInteger streak = consecutiveViolations.get(subsystem);
        if (streak != null) {
            streak.set(0);
        }
    }

    public void reset() {
        consecutiveViolations.clear();
        totalViolations.clear();
    }

    public int getConsecutiveViolations(String subsystem) {
        AtomicInteger streak = consecutiveViolations.get(subsystem);
        return streak == null ? 0 : streak.get();
    }

    public int getTotalViolations(String subsystem) {
        AtomicInteger total = totalViolations.get(subsystem);
        return total == null ? 0 : total.get();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getViolationsBeforeSuspend() {
        return violationsBeforeSuspend;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private boolean enabled = true;
        private boolean warnOnExceeded = true;
        private boolean suspendOnExceeded = true;
        private int violationsBeforeSuspend = 3;
        private final Map<String, Double> budgets = new java.util.HashMap<>();
        private double defaultBudgetMs = 5.0;

        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder warnOnExceeded(boolean v) {
            this.warnOnExceeded = v;
            return this;
        }

        public Builder suspendOnExceeded(boolean v) {
            this.suspendOnExceeded = v;
            return this;
        }

        public Builder violationsBeforeSuspend(int v) {
            this.violationsBeforeSuspend = v;
            return this;
        }

        public Builder budget(String subsystem, double budgetMs) {
            this.budgets.put(subsystem, budgetMs);
            return this;
        }

        public Builder defaultBudgetMs(double v) {
            this.defaultBudgetMs = v;
            return this;
        }

        public BudgetViolationTracker build() {
            return new BudgetViolationTracker(this);
        }
    }
}
