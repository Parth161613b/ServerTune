package com.servertune.selfmonitor;

import com.servertune.ServerTunePlugin;
import com.servertune.core.ModuleState;
import com.servertune.core.OptimizationModule;
import com.servertune.guard.BudgetViolationTracker;
import com.servertune.guard.GuardConfigLoader;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Times the optimizer's own subsystems and acts on what it measures.
 *
 * <p>Self-protection: when one of the plugin's own modules costs too much, warn, optionally
 * suspend <em>that module only</em>, and keep everything else - including the performance
 * guard - alive. {@link BudgetViolationTracker} holds the decision logic; this class applies
 * it, because suspending a module needs the plugin.
 *
 * <p>A suspended module is never resumed automatically here. It stays off until an operator
 * reloads or the guard's staged recovery resumes it, so a module that is genuinely too
 * expensive cannot re-suspend itself in a loop.
 */
public class SelfPerformanceMonitor {

    private final ServerTunePlugin plugin;
    private final Map<String, SubsystemMetrics> subsystemMetrics;

    /**
     * Volatile because {@link #reload()} replaces it wholesale while {@link #recordExecution} is
     * reading it from the timer path. Without it a reader could keep using the pre-reload tracker
     * indefinitely, enforcing budgets the operator had just changed, or - worse for a plain
     * reference publication - observe the new tracker before its constructor's writes were
     * visible. One volatile read per timed execution, no lock.
     */
    private volatile BudgetViolationTracker budgetTracker;

    /**
     * Console-rate limiting. Volatile for the same reason as the tracker: {@link #reload()}
     * replaces it while the timer path is reading it.
     */
    private volatile BudgetWarningPolicy warningPolicy;

    private final Set<String> suspendedForBudget = ConcurrentHashMap.newKeySet();

    /**
     * The timer currently open on this thread, so an inner timer can discount itself from the one
     * enclosing it. A ThreadLocal rather than a field because module timers run from the scheduler
     * and command paths can time work on other threads; a shared field would let one thread's timer
     * become another's parent and produce nonsense.
     */
    private final ThreadLocal<ExecutionTimer> activeTimer = new ThreadLocal<>();

    public SelfPerformanceMonitor(ServerTunePlugin plugin) {
        this.plugin = plugin;
        this.subsystemMetrics = new ConcurrentHashMap<>();
        this.budgetTracker = GuardConfigLoader.loadBudgetTracker(plugin.getConfig());
        this.warningPolicy = GuardConfigLoader.loadWarningPolicy(plugin.getConfig());
    }

    /**
     * Opens a timer for {@code subsystemName}, nested inside whatever timer is already open on this
     * thread. See {@link ExecutionTimer} for why nesting matters.
     */
    public ExecutionTimer startTimer(String subsystemName) {
        ExecutionTimer timer = new ExecutionTimer(this, subsystemName, activeTimer.get());
        activeTimer.set(timer);
        return timer;
    }

    /** Called by {@link ExecutionTimer#stop()} to pop the thread's timer stack. */
    void finishTimer(ExecutionTimer timer) {
        ExecutionTimer current = activeTimer.get();
        if (current != timer) {
            // Out-of-order stop; leave the stack alone rather than corrupt it.
            return;
        }
        ExecutionTimer parent = timer.getParent();
        if (parent == null) {
            activeTimer.remove();
        } else {
            activeTimer.set(parent);
        }
    }

    public void recordExecution(String subsystemName, double executionTimeMs) {
        SubsystemMetrics metrics = subsystemMetrics.computeIfAbsent(
                subsystemName,
                k -> new SubsystemMetrics(subsystemName)
        );

        metrics.recordExecution(executionTimeMs);

        try {
            enforceBudget(subsystemName, executionTimeMs);
        } catch (Exception e) {
            // Self-monitoring must never break the subsystem it is measuring.
            plugin.getLogger().log(Level.SEVERE, "Error enforcing budget for " + subsystemName, e);
        }
    }

    private void enforceBudget(String subsystemName, double executionTimeMs) {
        // Read the volatiles once. Re-reading them for the budget and the streak could straddle a
        // reload and report one tracker's threshold against another's counter.
        BudgetViolationTracker tracker = budgetTracker;
        BudgetWarningPolicy policy = warningPolicy;

        BudgetViolationTracker.Decision decision =
                tracker.record(subsystemName, executionTimeMs);

        double budget = tracker.budgetFor(subsystemName);
        long now = System.currentTimeMillis();

        if (decision == BudgetViolationTracker.Decision.OK) {
            // Feed the policy even on a good pass: it owns the over-budget -> within-budget
            // transition that produces the recovery line, and the status shown by
            // /serverhealth debug. A subsystem that never warned reports nothing here.
            if (policy.onWithinBudget(subsystemName, executionTimeMs, budget, now)
                    == BudgetWarningPolicy.Action.RECOVERED) {
                plugin.getLogger().info(String.format(
                        "[SelfMonitor] %s recovered within budget (%.2f ms vs %.2f ms).",
                        subsystemName, executionTimeMs, budget));
            }
            return;
        }

        int streak = tracker.getConsecutiveViolations(subsystemName);

        // SUSPEND is not rate-limited by the console policy, and is not silenced by it either.
        // It happens once, it changes plugin behaviour, and the operator has to be told even on
        // a server that has console logging off: only the repeating per-cycle warnings are a
        // logging concern. Suspension is a behaviour change and is reported as one.
        if (decision == BudgetViolationTracker.Decision.SUSPEND) {
            policy.onOverBudget(subsystemName, executionTimeMs, budget, now);
            suspendOverBudgetModule(subsystemName, executionTimeMs, budget, streak);
            return;
        }

        BudgetWarningPolicy.Action action =
                policy.onOverBudget(subsystemName, executionTimeMs, budget, now);
        if (action != BudgetWarningPolicy.Action.WARN) {
            // SUPPRESS or NONE: still measured, still counted, still visible in
            // /serverhealth debug - just not repeated on the console this cycle.
            return;
        }

        // The overruns swallowed during the window that just ended, not the one just started -
        // the policy restarts its running counter as it authorises this line.
        long suppressed = policy.getSuppressedAtLastWarn(subsystemName);
        String repeatNote = suppressed > 0
                ? String.format(" (%d further overrun(s) since the last warning)", suppressed)
                : "";

        // Verbose appends to a line that is already being printed. It cannot cause a line, and
        // it does not touch the cooldown that decided there would be one.
        String detail = policy.isVerbose() ? verboseDetail(subsystemName) : "";

        switch (decision) {
            case WARN -> plugin.getLogger().warning(String.format(
                    "[SelfMonitor] %s took %.2f ms, over its %.2f ms budget (%d/%d consecutive)%s%s",
                    subsystemName, executionTimeMs, budget, streak,
                    tracker.getViolationsBeforeSuspend(), repeatNote, detail));

            case WARN_PROTECTED -> plugin.getLogger().warning(String.format(
                    "[SelfMonitor] %s is over budget (%.2f ms vs %.2f ms) but is required for "
                            + "performance detection, so it will not be suspended%s%s",
                    subsystemName, executionTimeMs, budget, repeatNote, detail));

            default -> {
            }
        }
    }

    /**
     * The extra fields verbose mode appends. Reads counters this monitor already keeps - no
     * measurement, no allocation beyond the string itself, and only built when a line is about to
     * be printed anyway.
     */
    private String verboseDetail(String subsystemName) {
        SubsystemMetrics metrics = subsystemMetrics.get(subsystemName);
        if (metrics == null) {
            return "";
        }
        return String.format(" [avg %.2f ms, max %.2f ms over %d executions]",
                metrics.getAverageExecutionMs(), metrics.getMaxExecutionMs(),
                metrics.getExecutionCount());
    }

    private void suspendOverBudgetModule(String subsystemName, double executionTimeMs,
                                         double budget, int streak) {
        if (plugin.getModuleManager() == null) {
            return;
        }

        OptimizationModule module = plugin.getModuleManager().getModule(subsystemName);
        if (module == null || module.getState() != ModuleState.ENABLED) {
            // Nothing suspendable under that name; the warning above is all we can do.
            return;
        }

        try {
            module.suspend();
            suspendedForBudget.add(subsystemName);
            budgetTracker.clear(subsystemName);

            plugin.getLogger().severe(String.format(
                    "[SelfMonitor] Suspended module '%s': %.2f ms exceeded its %.2f ms budget "
                            + "%d times in a row. The rest of the plugin keeps running.",
                    subsystemName, executionTimeMs, budget, streak));
            plugin.getLogger().severe("[SelfMonitor] Re-enable it with /optimizer reload once "
                    + "the cause is addressed.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to suspend over-budget module: " + subsystemName, e);
        }
    }

    public SubsystemMetrics getMetrics(String subsystemName) {
        return subsystemMetrics.get(subsystemName);
    }

    public Map<String, SubsystemMetrics> getAllMetrics() {
        return Map.copyOf(subsystemMetrics);
    }

    public double getTotalAverageExecutionTime() {
        return subsystemMetrics.values().stream()
                .mapToDouble(SubsystemMetrics::getAverageExecutionMs)
                .sum();
    }

    /** Modules this monitor suspended for exceeding their budget. */
    public Set<String> getSuspendedForBudget() {
        return Set.copyOf(suspendedForBudget);
    }

    public BudgetViolationTracker getBudgetTracker() {
        return budgetTracker;
    }

    /** The console rate-limiter, and the source of the over-budget status shown by commands. */
    public BudgetWarningPolicy getWarningPolicy() {
        return warningPolicy;
    }

    /**
     * Re-reads the budgets and the logging settings from config.yml.
     *
     * <p>Deliberately narrow. {@code subsystemMetrics} is NOT cleared: the timings collected so
     * far are still true of this server and an operator who reloads to change a threshold should
     * not lose the history that made them change it. Only the config-derived objects are replaced.
     *
     * <p>A runtime logging override does not survive this. The policy is rebuilt from config, so
     * a reload puts console logging back to whatever config.yml says - which is the same thing a
     * restart does, and is what makes the override safe to hand out.
     */
    public void reload() {
        this.budgetTracker = GuardConfigLoader.loadBudgetTracker(plugin.getConfig());
        this.budgetTracker.reset();
        this.warningPolicy = GuardConfigLoader.loadWarningPolicy(plugin.getConfig());
        suspendedForBudget.clear();
    }

    public void reset() {
        subsystemMetrics.clear();
        budgetTracker.reset();
        warningPolicy.reset();
        suspendedForBudget.clear();
    }
}
