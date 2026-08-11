package com.servertune.guard;

import com.servertune.ServerTunePlugin;
import com.servertune.core.ModuleState;
import com.servertune.core.OptimizationModule;
import com.servertune.fallback.FallbackState;
import com.servertune.metrics.HealthSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Adaptive performance guard: the Bukkit-facing half of the state machine.
 *
 * <p>{@link PerformanceStateMachine} decides <em>what</em> state the server is in;
 * this class decides what to <em>do</em> about it, filtered through
 * {@link GuardActionPolicy} so an owner running alerts-only gets notifications and nothing
 * else.
 *
 * <p>Driven from {@link com.servertune.monitoring.HealthMonitor}, which already runs on
 * the main thread on the health interval, so no extra task is needed for evaluation.
 *
 * <p><b>Why this replaced the old FallbackController.</b> That class ran its own TPS-only
 * state machine with three defects, recorded here because they are the reason the logic was
 * rebuilt rather than patched:
 * <ul>
 *   <li>recovery was decided by {@code checkBelow(tps)} followed by
 *       {@code checkAbove(tps - 0.1)} on the same {@code SustainedThreshold}, so the sustained
 *       timer was reset by the first call before the second could ever observe it</li>
 *   <li>its {@code recoveryTask} field was never assigned, so cancelling recovery could not
 *       actually cancel the scheduled stages</li>
 *   <li>recovery stages 2 to 4 only logged; nothing resumed until stage 5</li>
 * </ul>
 * Two state machines reacting to the same snapshot would each suspend and resume modules, so
 * there is now exactly one owner of the state.
 */
public class PerformanceGuard {

    private final ServerTunePlugin plugin;

    private PerformanceStateMachine stateMachine;
    private GuardActionPolicy policy;
    private AdaptiveMonitoring adaptiveMonitoring;
    private int[] recoveryStageDelays;
    private boolean enabled;

    /** Every staged-recovery task, so cancelling recovery genuinely cancels all of it. */
    private final List<BukkitTask> recoveryTasks = new ArrayList<>();

    private volatile int recoveryStage = 0;
    private volatile boolean deepAnalysisAllowed = true;
    private volatile long fallbackEnteredAt = 0L;
    private volatile long lastFallbackDurationMillis = 0L;
    private volatile int fallbackEntryCount = 0;

    /** Modules this guard suspended, so recovery resumes exactly those and nothing else. */
    private final List<String> guardSuspendedModules = new ArrayList<>();

    public PerformanceGuard(ServerTunePlugin plugin) {
        this.plugin = plugin;
        loadConfiguration();
        this.stateMachine = new PerformanceStateMachine(
                GuardConfigLoader.loadThresholds(plugin.getConfig()), System.currentTimeMillis());
        logConfiguration();
    }

    private void loadConfiguration() {
        this.enabled = GuardConfigLoader.isEnabled(plugin.getConfig());
        this.policy = GuardConfigLoader.loadPolicy(plugin.getConfig());
        this.adaptiveMonitoring = GuardConfigLoader.loadAdaptiveMonitoring(
                plugin.getConfig(), plugin.getConfigManager().getHealthInterval());
        this.recoveryStageDelays = GuardConfigLoader.loadRecoveryStageDelays(plugin.getConfig());
    }

    private void logConfiguration() {
        GuardThresholds t = stateMachine.getThresholds();
        plugin.getLogger().info("Performance guard: " + (enabled ? "enabled" : "disabled"));
        if (!enabled) {
            return;
        }
        plugin.getLogger().info(String.format(
                "  WARNING at TPS < %.1f or MSPT > %.1fms | CRITICAL at TPS < %.1f or MSPT > %.1fms",
                t.getWarningTps(), t.getWarningMspt(), t.getCriticalTps(), t.getCriticalMspt()));
        plugin.getLogger().info(String.format(
                "  FALLBACK at TPS < %.1f for %ds | recovery at TPS >= %.1f for %ds",
                t.getFallbackTps(), t.getFallbackSustainedMillis() / 1000,
                t.getRecoveryTps(), t.getRecoverySustainedMillis() / 1000));
        plugin.getLogger().info("  Alerts: " + policy.isAlertsEnabled()
                + " | Automatic optimization: " + policy.isAutomaticOptimizationEnabled());

        if (policy.isAlertsEnabled() && !policy.isAutomaticOptimizationEnabled()) {
            plugin.getLogger().info("  Guard will report performance states but change nothing "
                    + "(set performance-guard.actions.automatic-optimization to enable actions)");
        }
    }

    /** Called once per health sample. */
    public void evaluate(HealthSnapshot snapshot) {
        if (!enabled || snapshot == null) {
            return;
        }

        try {
            PerformanceStateMachine.StateTransition transition = stateMachine.evaluate(
                    snapshot.getTps(), snapshot.getMspt(), System.currentTimeMillis());

            if (transition != null) {
                applyTransition(transition, snapshot);
            }
        } catch (Exception e) {
            // The guard must never take the health monitor down with it.
            plugin.getLogger().log(Level.SEVERE, "Error evaluating performance guard", e);
        }
    }

    private void applyTransition(PerformanceStateMachine.StateTransition transition,
                                 HealthSnapshot snapshot) {
        FallbackState to = transition.to();

        plugin.getLogger().info("[Guard] " + transition.describe());

        switch (to) {
            case FALLBACK -> enterFallback(transition);
            case RECOVERING -> beginRecovery(transition);
            default -> {
                // Leaving FALLBACK/RECOVERING by any other route must not leave work suspended.
                if (transition.from() == FallbackState.FALLBACK
                        || transition.from() == FallbackState.RECOVERING) {
                    cancelRecoveryTasks();
                    restoreSuspendedWork();
                }
                announce(to, describeMetrics(snapshot));
            }
        }

        applyModulePolicy(to);
        applyAdaptiveMonitoring(transition.from(), to);
    }

    private void enterFallback(PerformanceStateMachine.StateTransition transition) {
        cancelRecoveryTasks();
        recoveryStage = 0;
        fallbackEnteredAt = System.currentTimeMillis();
        fallbackEntryCount++;

        plugin.getLogger().severe("========================================");
        plugin.getLogger().severe("FALLBACK MODE ACTIVATED");
        plugin.getLogger().severe("Reason: " + transition.reason());
        plugin.getLogger().severe("Optimizer suspending its own work; commands and health "
                + "monitoring stay available");
        plugin.getLogger().severe("========================================");

        // Section 4: suspend optimizer actions, cancel expensive tasks, stop deep analysis,
        // keep commands, health monitoring and recovery detection running.
        suspendOptimizerWork();

        announce(FallbackState.FALLBACK,
                "§c§l[FALLBACK] §cOptimizer suspended - " + transition.reason());
    }

    private void suspendOptimizerWork() {
        try {
            if (policy.shouldSuspendOptimizerInFallback()) {
                recordAndSuspendRunningModules();
            }

            if (policy.shouldCancelExpensiveTasksInFallback() && plugin.getScheduler() != null) {
                int cancelled = plugin.getScheduler().cancelModuleTasks();
                plugin.getLogger().info("[Guard] Cancelled " + cancelled + " optimizer task(s)");
            }

            if (policy.shouldStopDeepAnalysisInFallback()) {
                deepAnalysisAllowed = false;
                plugin.getLogger().info("[Guard] Deep analysis stopped");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error suspending optimizer work", e);
        }
    }

    private void recordAndSuspendRunningModules() {
        guardSuspendedModules.clear();

        for (OptimizationModule module : plugin.getModuleManager().getAllModules().values()) {
            if (module.getState() != ModuleState.ENABLED) {
                continue;
            }
            try {
                module.suspend();
                guardSuspendedModules.add(module.getName());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to suspend module: " + module.getName(), e);
            }
        }

        if (!guardSuspendedModules.isEmpty()) {
            plugin.getLogger().info("[Guard] Suspended module(s): "
                    + String.join(", ", guardSuspendedModules));
        }
    }

    // ------------------------------------------------------------------
    // Recovery (section 6)
    // ------------------------------------------------------------------

    private void beginRecovery(PerformanceStateMachine.StateTransition transition) {
        lastFallbackDurationMillis = System.currentTimeMillis() - fallbackEnteredAt;

        plugin.getLogger().info("========================================");
        plugin.getLogger().info("RECOVERY INITIATED");
        plugin.getLogger().info("Reason: " + transition.reason());
        plugin.getLogger().info("Time in fallback: " + (lastFallbackDurationMillis / 1000) + "s");
        plugin.getLogger().info("========================================");

        recoveryStage = 1;
        scheduleRecoveryStages();

        announce(FallbackState.RECOVERING,
                "§a§l[RECOVERY] §aPerformance stable - resuming optimizer in stages");
    }

    /**
     * Six staged steps, each a tracked task so {@link #cancelRecoveryTasks()} can stop the
     * whole sequence if the server relapses. Nothing heavy resumes early: monitoring comes
     * back first, then the trackers, then the modules, and only last the deep analysis.
     */
    private void scheduleRecoveryStages() {
        cancelRecoveryTasks();

        stage(2, "Resuming health monitoring at normal cadence", () ->
                applyAdaptiveMonitoring(FallbackState.FALLBACK, FallbackState.RECOVERING));

        stage(3, "Resuming entity tracking", () -> resumeModules("item-optimization",
                "xp-optimization", "projectile-optimization", "entity-optimization"));

        stage(4, "Resuming chunk and block-entity tracking", () ->
                resumeModules("chunk-optimization", "hopper-optimization"));

        stage(5, "Resuming remaining optimization modules", () -> {
            List<String> remaining = new ArrayList<>(guardSuspendedModules);
            resumeModules(remaining.toArray(new String[0]));
        });

        stage(6, "Resuming deep analysis", () -> {
            deepAnalysisAllowed = true;
            restartModuleTasks();
            completeRecovery();
        });
    }

    private void stage(int number, String description, Runnable action) {
        int delaySeconds = recoveryStageDelays[number];

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (stateMachine.getCurrentState() != FallbackState.RECOVERING) {
                return;
            }
            plugin.getLogger().info("[Recovery Stage " + number + "/6] " + description);
            recoveryStage = number;
            try {
                action.run();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Recovery stage " + number + " failed", e);
            }
        }, delaySeconds * 20L);

        recoveryTasks.add(task);
    }

    private void completeRecovery() {
        PerformanceStateMachine.StateTransition transition =
                stateMachine.completeRecovery(System.currentTimeMillis());

        recoveryStage = 0;
        cancelRecoveryTasks();

        if (transition != null) {
            plugin.getLogger().info("[Guard] " + transition.describe());
            applyAdaptiveMonitoring(FallbackState.RECOVERING, transition.to());
        }

        announce(FallbackState.NORMAL, "§a[RECOVERY] §aOptimizer fully operational");
    }

    private void cancelRecoveryTasks() {
        for (BukkitTask task : recoveryTasks) {
            try {
                task.cancel();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to cancel recovery task: " + e.getMessage());
            }
        }
        recoveryTasks.clear();
    }

    /** Puts everything back after leaving fallback by a route other than staged recovery. */
    private void restoreSuspendedWork() {
        deepAnalysisAllowed = true;
        resumeModules(guardSuspendedModules.toArray(new String[0]));
        restartModuleTasks();
    }

    /**
     * Resumes only modules this guard suspended. A module the owner disabled in config was
     * never in {@code guardSuspendedModules}, so recovery can never switch it on.
     */
    private void resumeModules(String... names) {
        for (String name : names) {
            if (!guardSuspendedModules.contains(name)) {
                continue;
            }

            OptimizationModule module = plugin.getModuleManager().getModule(name);
            if (module == null || module.getState() != ModuleState.SUSPENDED) {
                guardSuspendedModules.remove(name);
                continue;
            }

            try {
                module.resume();
                guardSuspendedModules.remove(name);
                plugin.getLogger().info("[Guard] Resumed module: " + name);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to resume module: " + name, e);
            }
        }
    }

    private void restartModuleTasks() {
        if (plugin.getScheduler() == null) {
            return;
        }
        try {
            int restored = plugin.getScheduler().restartModuleTasks();
            if (restored > 0) {
                plugin.getLogger().info("[Guard] Restored " + restored + " optimizer task(s)");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to restart optimizer tasks", e);
        }
    }

    // ------------------------------------------------------------------
    // Owner-configured per-state actions (sections 2, 3, 7)
    // ------------------------------------------------------------------

    /**
     * Applies the owner's {@code enable-modules} / {@code suspend-modules} lists for the new
     * state. Both are empty unless {@code automatic-optimization} is on, so by default this
     * is a no-op and the guard is purely an alerting system.
     */
    private void applyModulePolicy(FallbackState state) {
        if (!policy.isAutomaticOptimizationEnabled()) {
            return;
        }

        for (String name : policy.modulesToSuspend(state)) {
            OptimizationModule module = plugin.getModuleManager().getModule(name);
            if (module == null || module.getState() != ModuleState.ENABLED) {
                continue;
            }
            try {
                module.suspend();
                if (!guardSuspendedModules.contains(name)) {
                    guardSuspendedModules.add(name);
                }
                plugin.getLogger().info("[Guard] " + state + ": suspended " + name);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to suspend module: " + name, e);
            }
        }

        for (String name : policy.modulesToEnable(state)) {
            OptimizationModule module = plugin.getModuleManager().getModule(name);
            if (module == null || module.getState() == ModuleState.ENABLED) {
                continue;
            }
            try {
                if (module.getState() == ModuleState.SUSPENDED) {
                    module.resume();
                } else {
                    module.enable();
                }
                guardSuspendedModules.remove(name);
                plugin.getLogger().info("[Guard] " + state + ": enabled " + name);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to enable module: " + name, e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Adaptive monitoring (section 5)
    // ------------------------------------------------------------------

    private void applyAdaptiveMonitoring(FallbackState from, FallbackState to) {
        if (!adaptiveMonitoring.isEnabled() || plugin.getScheduler() == null) {
            return;
        }
        if (!adaptiveMonitoring.requiresReschedule(from, to)) {
            return;
        }

        int interval = adaptiveMonitoring.intervalFor(to);
        try {
            plugin.getScheduler().rescheduleHealthMonitor(interval);
            plugin.getLogger().info("[Guard] Health monitor interval now " + interval
                    + " ticks (" + to + ")");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to reschedule health monitor", e);
        }
    }

    // ------------------------------------------------------------------
    // Notification
    // ------------------------------------------------------------------

    private void announce(FallbackState state, String message) {
        if (!policy.shouldNotify(state)) {
            return;
        }

        String permission = policy.getAlertPermission();
        Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission(permission))
                .forEach(player -> player.sendMessage("§8[§6ServerTune§8]§r " + message));

        if (policy.isLogToConsole()) {
            plugin.getLogger().warning(message);
        }
    }

    private String describeMetrics(HealthSnapshot snapshot) {
        String colour = switch (stateMachine.getCurrentState()) {
            case NORMAL -> "§a";
            case WARNING -> "§e";
            case CRITICAL -> "§6";
            case FALLBACK -> "§c";
            case RECOVERING -> "§b";
        };
        return String.format("%s[%s] §7TPS %.2f, MSPT %.2fms",
                colour, stateMachine.getCurrentState(), snapshot.getTps(), snapshot.getMspt());
    }

    // ------------------------------------------------------------------
    // Accessors and lifecycle
    // ------------------------------------------------------------------

    /**
     * False while the server is in fallback with deep analysis stopped. Callers that run
     * expensive periodic analysis check this before doing work.
     */
    public boolean isDeepAnalysisAllowed() {
        return deepAnalysisAllowed;
    }

    public FallbackState getCurrentState() {
        return stateMachine.getCurrentState();
    }

    public boolean isInFallback() {
        return stateMachine.getCurrentState() == FallbackState.FALLBACK;
    }

    public boolean isRecovering() {
        return stateMachine.getCurrentState() == FallbackState.RECOVERING;
    }

    public int getRecoveryStage() {
        return recoveryStage;
    }

    public int getFallbackEntryCount() {
        return fallbackEntryCount;
    }

    public long getLastFallbackDurationMillis() {
        return lastFallbackDurationMillis;
    }

    public long getTimeInStateMillis() {
        return stateMachine.getTimeInState(System.currentTimeMillis());
    }

    public int getTransitionCount() {
        return stateMachine.getTransitionCount();
    }

    public List<String> getGuardSuspendedModules() {
        return List.copyOf(guardSuspendedModules);
    }

    public GuardActionPolicy getPolicy() {
        return policy;
    }

    public AdaptiveMonitoring getAdaptiveMonitoring() {
        return adaptiveMonitoring;
    }

    public PerformanceStateMachine getStateMachine() {
        return stateMachine;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Re-reads the guard's configuration, then re-asserts whatever the current state requires.
     *
     * <p>Must be called after the scheduler restarts and the modules reload, because both of
     * those turn on everything config.yml asks for without knowing the server is struggling.
     * A reload issued during fallback would otherwise put every module and every optimizer task
     * back to work while the state machine still read FALLBACK - the guard would believe its work
     * was suspended, and staged recovery would later try to resume modules that never stopped.
     *
     * <p>The state machine itself is left alone. Its history is what the server is actually
     * doing, and an operator editing config.yml has not made a struggling server healthy.
     */
    public void reload() {
        loadConfiguration();
        stateMachine.updateThresholds(GuardConfigLoader.loadThresholds(plugin.getConfig()));
        logConfiguration();

        if (!enabled) {
            // The guard was just switched off. Release anything it was holding down, otherwise
            // those modules stay suspended with nothing left running to resume them.
            cancelRecoveryTasks();
            restoreSuspendedWork();
            return;
        }

        FallbackState current = stateMachine.getCurrentState();
        if (current == FallbackState.FALLBACK) {
            plugin.getLogger().warning("[Guard] Reloaded while in FALLBACK; re-suspending "
                    + "optimizer work that the reload restarted");
            suspendOptimizerWork();
        } else if (current == FallbackState.RECOVERING) {
            // Recovery is a timed sequence whose remaining stages assume the earlier ones ran.
            // The reload invalidated that, so restart the sequence from stage 1.
            plugin.getLogger().info("[Guard] Reloaded while RECOVERING; restarting the staged "
                    + "recovery sequence");
            recoveryStage = 1;
            scheduleRecoveryStages();
        }

        reassertMonitoringInterval(current);
    }

    /**
     * Puts the health monitor back on the interval the current state calls for.
     *
     * <p>Separate from {@link #applyAdaptiveMonitoring} because that method compares two states
     * and skips the reschedule when they match - correct on a transition, wrong here. A reload
     * restarts the scheduler, which schedules the monitor at the plain configured interval, so
     * after a reload in WARNING or FALLBACK the interval has to be re-applied even though the
     * state did not change.
     */
    private void reassertMonitoringInterval(FallbackState state) {
        if (!adaptiveMonitoring.isEnabled() || plugin.getScheduler() == null) {
            return;
        }

        int interval = adaptiveMonitoring.intervalFor(state);
        try {
            plugin.getScheduler().rescheduleHealthMonitor(interval);
            plugin.getLogger().info("[Guard] Health monitor interval now " + interval
                    + " ticks (" + state + ")");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to reschedule health monitor", e);
        }
    }

    public void shutdown() {
        cancelRecoveryTasks();
        guardSuspendedModules.clear();
        deepAnalysisAllowed = true;
    }
}
