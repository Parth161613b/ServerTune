package com.servertune.selfmonitor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether an over-budget subsystem should print to console this cycle.
 *
 * <p>The measurement itself is never gated by this class. {@link SelfPerformanceMonitor} records
 * every execution and {@code BudgetViolationTracker} counts every violation regardless of what is
 * decided here - this only answers "say it out loud?". A subsystem that is permanently over budget
 * is still permanently over budget in {@code /serverhealth debug}; it just does not repeat itself
 * on the console every monitoring cycle. Server health alerting - TPS, MSPT, fallback - is a
 * different subsystem entirely and never passes through here.
 *
 * <p>Cooldown is tracked per subsystem, so a noisy {@code health-monitor} cannot mask a newly
 * misbehaving module: each name has its own timer and its own first warning fires immediately.
 *
 * <p><b>Runtime override.</b> {@code /servertune debug selfmonitor on} flips a field on the live
 * policy. It is a field and not a config write on purpose: turning debug logging on to look at
 * something must not edit the operator's config.yml, and it must not survive a restart. The
 * override is dropped on reload, which rebuilds this object from config.
 *
 * <p>Bukkit-free and clock-injected, so the cooldown and recovery behaviour is reachable from a
 * unit test - paper-api is {@code compileOnly}, so anything holding a Bukkit type is not.
 */
public final class BudgetWarningPolicy {

    /** What the caller should print, if anything. */
    public enum Action {
        /** Print the over-budget warning. */
        WARN,
        /** Over budget, but an identical warning is still inside its cooldown window. */
        SUPPRESS,
        /** Back inside budget after having warned; print the recovery line once. */
        RECOVERED,
        /** Nothing to say. */
        NONE
    }

    /** Whether console logging is on, and what turned it on. */
    public enum LoggingSource {
        /** Off in config, and no runtime override. */
        OFF,
        /** On because config.yml says so. */
        CONFIG,
        /** On because an operator ran the runtime debug command. */
        RUNTIME,
        /** Off because an operator turned it off at runtime, overriding config. */
        RUNTIME_OFF
    }

    /** Whether the monitor as a whole runs. Not a logging setting; see the class note. */
    private final boolean monitoringEnabled;

    private final SelfMonitorLogging configured;

    /**
     * The runtime override, or null when config decides. Volatile rather than locked: it is
     * written once per operator command and read once per timed execution, so a plain field
     * could leave the timer path reading a stale value indefinitely, and a lock would put
     * contention on the hot path to serialise a boolean.
     */
    private volatile Boolean runtimeLogging;

    /** Runtime verbose override, same reasoning as {@link #runtimeLogging}. */
    private volatile Boolean runtimeVerbose;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private static final class Entry {
        volatile boolean overBudget;
        volatile boolean warnedWhileOver;
        /** Distinct from {@link #warnedWhileOver}, which is cleared on recovery. */
        volatile boolean everWarned;
        volatile long lastWarnAt;
        volatile double lastValueMs;
        volatile double budgetMs;
        volatile long suppressedSinceWarn;
        /** The value {@link #suppressedSinceWarn} held when the last warning printed. */
        volatile long suppressedAtLastWarn;
        volatile long totalSuppressed;
    }

    /**
     * Immutable view for reporting; carries no reference to the live entry.
     *
     * <p>{@code everWarned} is a field rather than {@code lastWarnAt > 0}: a timestamp of zero is a
     * legitimate instant, and deriving "has this warned" from it conflates never-warned with
     * warned-at-epoch. That conflation also read as an expired cooldown, so the first repeat after
     * a warning printed again instead of being suppressed.
     */
    public record Status(String subsystem, double lastValueMs, double budgetMs,
                         boolean overBudget, boolean everWarned, long lastWarnAt,
                         long suppressedSinceWarn, long totalSuppressed) {

        public boolean hasWarned() {
            return everWarned;
        }
    }

    public BudgetWarningPolicy(boolean monitoringEnabled, SelfMonitorLogging configured) {
        this.monitoringEnabled = monitoringEnabled;
        this.configured = configured == null ? SelfMonitorLogging.defaults() : configured;
    }

    /**
     * Records that {@code subsystem} came in over budget and decides whether to print.
     *
     * <p>The overrun is recorded whatever the logging settings say, because
     * {@code /serverhealth debug} reads this state and has to be accurate on a silent server.
     *
     * <p>The first overrun after a quiet period always prints, so a new problem is never delayed by
     * the cooldown. Subsequent overruns inside the window are counted and suppressed, and the count
     * is handed back on the next warning so the operator can see how long it has been going on.
     */
    public Action onOverBudget(String subsystem, double valueMs, double budgetMs, long nowMillis) {
        Entry entry = entries.computeIfAbsent(subsystem, k -> new Entry());
        entry.lastValueMs = valueMs;
        entry.budgetMs = budgetMs;
        entry.overBudget = true;

        if (!isOverBudgetLoggingActive()) {
            return Action.NONE;
        }

        // warnedWhileOver is the whole condition: it is false before the first warning of a
        // stretch and cleared again on recovery, so a new problem always prints immediately and a
        // continuing one waits out the window.
        boolean due = !entry.warnedWhileOver
                || nowMillis - entry.lastWarnAt >= configured.cooldownMillis();

        if (!due) {
            entry.suppressedSinceWarn++;
            entry.totalSuppressed++;
            return Action.SUPPRESS;
        }

        entry.lastWarnAt = nowMillis;
        entry.warnedWhileOver = true;
        entry.everWarned = true;
        // The count is handed to the caller through getSuppressedAtLastWarn(), which is read
        // after this method returns, so it is banked before the running counter restarts.
        // Without the restart the next warning would restate this stretch's overruns on top
        // of its own, and the figure would only ever grow.
        entry.suppressedAtLastWarn = entry.suppressedSinceWarn;
        entry.suppressedSinceWarn = 0L;
        return Action.WARN;
    }

    /**
     * Records that {@code subsystem} came in within budget.
     *
     * <p>Returns {@link Action#RECOVERED} exactly once per over-to-within transition, and only if
     * this subsystem had actually warned - a subsystem that was never noisy has nothing to recover
     * from, and announcing it would be its own kind of spam. The transition state is cleared
     * whether or not the line is printed, so enabling recovery logging later cannot produce a
     * recovery line for something that recovered while it was off.
     */
    public Action onWithinBudget(String subsystem, double valueMs, double budgetMs,
                                 long nowMillis) {
        Entry entry = entries.get(subsystem);
        if (entry == null) {
            return Action.NONE;
        }

        entry.lastValueMs = valueMs;
        entry.budgetMs = budgetMs;

        boolean wasOver = entry.overBudget;
        boolean hadWarned = entry.warnedWhileOver;
        entry.overBudget = false;
        entry.warnedWhileOver = false;
        entry.suppressedSinceWarn = 0L;

        if (!isRecoveryLoggingActive() || !wasOver || !hadWarned) {
            return Action.NONE;
        }
        return Action.RECOVERED;
    }

    /**
     * Overruns swallowed since the last printed warning for this subsystem. Restarts at zero
     * each time a warning prints, so it always answers "how many since the line you last saw".
     */
    public long getSuppressedSinceWarn(String subsystem) {
        Entry entry = entries.get(subsystem);
        return entry == null ? 0L : entry.suppressedSinceWarn;
    }

    /**
     * The count the warning currently being printed should quote.
     *
     * <p>Separate from {@link #getSuppressedSinceWarn} because the two readers want different
     * things at the same instant: the line being composed wants the stretch that just ended,
     * while the entry has already started counting the next one. Reading the live counter here
     * would print zero on every warning, and not resetting it would make each warning restate
     * every overrun since the subsystem first went over.
     */
    public long getSuppressedAtLastWarn(String subsystem) {
        Entry entry = entries.get(subsystem);
        return entry == null ? 0L : entry.suppressedAtLastWarn;
    }

    public Status statusOf(String subsystem) {
        Entry entry = entries.get(subsystem);
        if (entry == null) {
            return null;
        }
        return toStatus(subsystem, entry);
    }

    /** Snapshot of every subsystem this policy has seen, for {@code /serverhealth debug}. */
    public Map<String, Status> statuses() {
        Map<String, Status> out = new java.util.HashMap<>(entries.size());
        entries.forEach((name, entry) -> out.put(name, toStatus(name, entry)));
        return Map.copyOf(out);
    }

    private static Status toStatus(String subsystem, Entry entry) {
        return new Status(subsystem, entry.lastValueMs, entry.budgetMs, entry.overBudget,
                entry.everWarned, entry.lastWarnAt, entry.suppressedSinceWarn,
                entry.totalSuppressed);
    }

    // ---------------------------------------------------------------------------------------
    // Logging state
    // ---------------------------------------------------------------------------------------

    /**
     * Whether console logging is on right now, from either source. Self-monitoring being disabled
     * outright wins over everything: there is nothing being measured to talk about.
     */
    public boolean isLoggingActive() {
        if (!monitoringEnabled) {
            return false;
        }
        Boolean override = runtimeLogging;
        return override != null ? override : configured.enabled();
    }

    /** Whether over-budget warnings specifically will print. */
    public boolean isOverBudgetLoggingActive() {
        return isLoggingActive() && configured.overBudget();
    }

    /** Whether the one-per-transition recovery line will print. */
    public boolean isRecoveryLoggingActive() {
        return isLoggingActive() && configured.recovery();
    }

    /**
     * Whether printed lines carry extra detail. Verbose adds fields to lines that were going to be
     * printed anyway - it never lowers a cooldown, never bypasses one, and adds no periodic
     * output, so there is no setting here that can produce per-tick logging.
     */
    public boolean isVerbose() {
        if (!isLoggingActive()) {
            return false;
        }
        Boolean override = runtimeVerbose;
        return override != null ? override : configured.verbose();
    }

    /** What is deciding {@link #isLoggingActive()}, for the status command. */
    public LoggingSource getLoggingSource() {
        Boolean override = runtimeLogging;
        if (override != null) {
            return override ? LoggingSource.RUNTIME : LoggingSource.RUNTIME_OFF;
        }
        return configured.enabled() && monitoringEnabled ? LoggingSource.CONFIG : LoggingSource.OFF;
    }

    /**
     * Turns console logging on or off until the next reload or restart. Writes one field; touches
     * no file, schedules nothing, and leaves every recorded measurement in place.
     */
    public void setRuntimeLogging(boolean on) {
        this.runtimeLogging = on;
    }

    /** Same, for verbose detail. Switching verbose on does not switch logging on. */
    public void setRuntimeVerbose(boolean on) {
        this.runtimeVerbose = on;
    }

    /** Drops both overrides so config.yml decides again. */
    public void clearRuntimeOverrides() {
        this.runtimeLogging = null;
        this.runtimeVerbose = null;
    }

    public boolean hasRuntimeOverride() {
        return runtimeLogging != null || runtimeVerbose != null;
    }

    /** The settings as read from config.yml, ignoring any runtime override. */
    public SelfMonitorLogging getConfigured() {
        return configured;
    }

    public boolean isMonitoringEnabled() {
        return monitoringEnabled;
    }

    public long getCooldownMillis() {
        return configured.cooldownMillis();
    }

    public void reset() {
        entries.clear();
    }
}
