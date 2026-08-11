package com.servertune.guard;

import com.servertune.fallback.FallbackState;
import com.servertune.selfmonitor.BudgetWarningPolicy;
import com.servertune.selfmonitor.SelfMonitorLogging;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Turns config.yml into the guard's Bukkit-free value objects.
 *
 * <p>Kept separate from {@link PerformanceGuard} so that everything the guard decides can
 * be unit tested by constructing the value objects directly, with no server present.
 *
 * <p>Thresholds fall back to the older {@code alerts.*} and {@code fallback.*} keys when the
 * newer {@code performance-guard.*} keys are absent, so an existing config.yml keeps working
 * and an owner who already tuned their alert thresholds does not have to restate them.
 */
public final class GuardConfigLoader {

    private static final String ROOT = "performance-guard";

    private GuardConfigLoader() {
    }

    /**
     * The guard replaced the old fallback controller, so an existing config that only sets
     * {@code fallback.enabled: false} still turns the whole thing off.
     */
    public static boolean isEnabled(FileConfiguration config) {
        return config.getBoolean(ROOT + ".enabled",
                config.getBoolean("fallback.enabled", true));
    }

    public static GuardThresholds loadThresholds(FileConfiguration config) {
        String s = ROOT + ".states.";
        return GuardThresholds.builder()
                .warningTps(config.getDouble(s + "warning.tps",
                        config.getDouble("alerts.tps.warning.threshold", 18.0)))
                // MSPT deliberately does NOT inherit from alerts.mspt.*. Those are alerting
                // thresholds (40/50ms) and 50ms is exactly 20 TPS, so reusing them here would
                // put a healthy server permanently in CRITICAL.
                .warningMspt(config.getDouble(s + "warning.mspt", 55.0))
                .warningSustainedSeconds(config.getInt(s + "warning.sustained-seconds",
                        config.getInt("alerts.tps.warning.sustained-seconds", 10)))
                .criticalTps(config.getDouble(s + "critical.tps",
                        config.getDouble("alerts.tps.critical.threshold", 15.0)))
                .criticalMspt(config.getDouble(s + "critical.mspt", 70.0))
                .criticalSustainedSeconds(config.getInt(s + "critical.sustained-seconds",
                        config.getInt("alerts.tps.critical.sustained-seconds", 5)))
                .fallbackTps(config.getDouble(s + "fallback.tps",
                        config.getDouble("fallback.trigger.tps", 10.0)))
                .fallbackSustainedSeconds(config.getInt(s + "fallback.sustained-seconds",
                        config.getInt("fallback.trigger.sustained-seconds", 3)))
                .recoveryTps(config.getDouble(s + "recovery.tps",
                        config.getDouble("fallback.recovery.tps", 18.0)))
                .recoverySustainedSeconds(config.getInt(s + "recovery.sustained-seconds",
                        config.getInt("fallback.recovery.sustained-seconds", 10)))
                .deEscalationSustainedSeconds(
                        config.getInt(ROOT + ".stability.de-escalation-sustained-seconds", 10))
                .minStateSeconds(config.getInt(ROOT + ".stability.min-state-seconds", 5))
                .tpsHysteresis(config.getDouble(ROOT + ".stability.tps-hysteresis", 1.0))
                .msptHysteresis(config.getDouble(ROOT + ".stability.mspt-hysteresis", 5.0))
                .build();
    }

    public static GuardActionPolicy loadPolicy(FileConfiguration config) {
        GuardActionPolicy.Builder builder = GuardActionPolicy.builder()
                .alertsEnabled(config.getBoolean(ROOT + ".actions.alerts", true))
                .automaticOptimizationEnabled(
                        config.getBoolean(ROOT + ".actions.automatic-optimization", false))
                .alertPermission(config.getString(ROOT + ".actions.alert-permission", "serverhealth.alert"))
                .logToConsole(config.getBoolean(ROOT + ".actions.log-to-console", true))
                .cancelExpensiveTasksInFallback(
                        config.getBoolean(ROOT + ".fallback-actions.cancel-expensive-tasks", true))
                .stopDeepAnalysisInFallback(
                        config.getBoolean(ROOT + ".fallback-actions.stop-deep-analysis", true));

        for (FallbackState state : FallbackState.values()) {
            builder.stateActions(state, loadStateActions(config, state));
        }

        return builder.build();
    }

    private static GuardActionPolicy.StateActions loadStateActions(FileConfiguration config,
                                                                  FallbackState state) {
        String path = ROOT + ".actions." + state.name().toLowerCase();
        ConfigurationSection section = config.getConfigurationSection(path);

        if (section == null) {
            // No section: notify for every state except NORMAL, change nothing.
            return state == FallbackState.NORMAL
                    ? GuardActionPolicy.StateActions.none()
                    : GuardActionPolicy.StateActions.notifyOnly();
        }

        boolean notify = section.getBoolean("notify-staff", state != FallbackState.NORMAL);
        List<String> enable = section.getStringList("enable-modules");
        List<String> suspend = section.getStringList("suspend-modules");

        return new GuardActionPolicy.StateActions(notify, enable, suspend);
    }

    public static AdaptiveMonitoring loadAdaptiveMonitoring(FileConfiguration config,
                                                            int baseIntervalTicks) {
        String a = ROOT + ".adaptive-monitoring.";
        return AdaptiveMonitoring.builder()
                .enabled(config.getBoolean(a + "enabled", true))
                .baseIntervalTicks(baseIntervalTicks)
                .normalMultiplier(config.getDouble(a + "multipliers.normal", 1.0))
                .warningMultiplier(config.getDouble(a + "multipliers.warning", 0.75))
                .criticalMultiplier(config.getDouble(a + "multipliers.critical", 0.5))
                .fallbackMultiplier(config.getDouble(a + "multipliers.fallback", 1.5))
                .recoveringMultiplier(config.getDouble(a + "multipliers.recovering", 1.0))
                .build();
    }

    public static BudgetViolationTracker loadBudgetTracker(FileConfiguration config) {
        double moduleBudget = config.getDouble("self-monitoring.budget.optimization-module", 5.0);

        BudgetViolationTracker.Builder builder = BudgetViolationTracker.builder()
                .enabled(config.getBoolean("self-monitoring.enabled", true))
                .warnOnExceeded(config.getBoolean("self-monitoring.warn-on-budget-exceeded", true))
                .suspendOnExceeded(config.getBoolean("self-monitoring.suspend-on-budget-exceeded", true))
                .violationsBeforeSuspend(config.getInt("self-monitoring.violations-before-suspend", 3))
                .defaultBudgetMs(moduleBudget)
                // Only the timers that actually exist. entity-tracking and chunk-analysis were
                // registered here too, but nothing ever calls startTimer under those names -
                // both trackers are event-driven - so those budgets could never be consulted.
                .budget("health-monitor", config.getDouble("self-monitoring.budget.health-monitor", 5.0))
                .budget("metrics-collection", config.getDouble("self-monitoring.budget.metrics-collection", 5.0))
                .budget("deep-analysis", config.getDouble("self-monitoring.budget.deep-analysis", 10.0));

        return builder.build();
    }

    /**
     * Console logging settings for self-monitoring. See {@link SelfMonitorLogging} for why the
     * defaults are silent, and {@code ConfigRules.resolveLogCooldownSeconds} for what happens to
     * a cooldown of 0 or a very small one.
     */
    public static SelfMonitorLogging loadSelfMonitorLogging(FileConfiguration config) {
        String root = "self-monitoring.logging.";
        return SelfMonitorLogging.of(
                config.getBoolean(root + "enabled", SelfMonitorLogging.DEFAULT_ENABLED),
                config.getBoolean(root + "over-budget", SelfMonitorLogging.DEFAULT_OVER_BUDGET),
                config.getBoolean(root + "recovery", SelfMonitorLogging.DEFAULT_RECOVERY),
                config.getBoolean(root + "verbose", SelfMonitorLogging.DEFAULT_VERBOSE),
                config.getInt(root + "cooldown-seconds",
                        SelfMonitorLogging.DEFAULT_COOLDOWN_SECONDS));
    }

    /** Console warning cooldown and recovery reporting, per subsystem. See BudgetWarningPolicy. */
    public static BudgetWarningPolicy loadWarningPolicy(FileConfiguration config) {
        return new BudgetWarningPolicy(
                config.getBoolean("self-monitoring.enabled", true),
                loadSelfMonitorLogging(config));
    }

    /** Recovery stage delays in seconds, index 0 unused so stage N reads at index N. */
    public static int[] loadRecoveryStageDelays(FileConfiguration config) {
        return new int[] {
                0,
                0,
                config.getInt("fallback.recovery-stages.stage-2-delay", 5),
                config.getInt("fallback.recovery-stages.stage-3-delay", 10),
                config.getInt("fallback.recovery-stages.stage-4-delay", 15),
                config.getInt("fallback.recovery-stages.stage-5-delay", 20),
                config.getInt("fallback.recovery-stages.stage-6-delay", 30)
        };
    }
}
