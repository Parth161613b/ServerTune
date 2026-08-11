package com.servertune.config;

import com.servertune.pluginscan.PluginScanSettings;
import com.servertune.selfmonitor.SelfMonitorLogging;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Logger;

/**
 * Corrects unusable values in config.yml at load and at reload.
 *
 * <p>A thin shell: this class knows which paths to read and how to write a replacement back,
 * {@link ConfigRules} knows whether a value is usable and what replaces it if not. The split is
 * what makes the judgement testable - paper-api is {@code compileOnly}, so anything holding a
 * {@link FileConfiguration} cannot be reached from a unit test.
 *
 * <p>Corrections are in-memory only. config.yml on disk is never rewritten, so the operator's
 * file keeps their mistake and their comments, and the logged warning tells them where to look.
 */
public class ConfigValidator {

    public static void validate(FileConfiguration config, Logger logger) {
        // Performance profile
        apply(config, logger, ConfigRules.profile("performance.profile",
                config.getString("performance.profile", "balanced"), "balanced"));

        // Scheduling intervals, in ticks
        positive(config, logger, "performance.intervals.health", 100);
        positive(config, logger, "performance.intervals.deep-analysis", 1200);

        // Module intervals. A zero or negative period would make the scheduler run the
        // module every tick, which is the opposite of what this plugin is for.
        positive(config, logger, "performance.intervals.modules.item-merge", 200);
        positive(config, logger, "performance.intervals.modules.entity-cleanup", 400);
        positive(config, logger, "performance.intervals.modules.chunk-unload", 600);
        positive(config, logger, "performance.intervals.modules.hopper", 400);
        positive(config, logger, "performance.intervals.modules.redstone", 400);

        // TPS thresholds. Above 20 can never trigger; below 0 is meaningless.
        tps(config, logger, "alerts.tps.warning.threshold", 18.0);
        tps(config, logger, "alerts.tps.critical.threshold", 15.0);
        tps(config, logger, "alerts.tps.emergency.threshold", 10.0);
        tps(config, logger, "fallback.trigger.tps", 10.0);
        tps(config, logger, "fallback.recovery.tps", 18.0);

        // MSPT thresholds. Unbounded above - a badly stalled server really can sit at 2000ms.
        positive(config, logger, "alerts.mspt.warning.threshold", 40.0);
        positive(config, logger, "alerts.mspt.critical.threshold", 50.0);

        // Sustained durations, in seconds
        positive(config, logger, "alerts.tps.warning.sustained-seconds", 10);
        positive(config, logger, "alerts.tps.critical.sustained-seconds", 5);
        positive(config, logger, "alerts.tps.emergency.sustained-seconds", 3);
        positive(config, logger, "alerts.mspt.warning.sustained-seconds", 10);
        positive(config, logger, "alerts.mspt.critical.sustained-seconds", 5);
        positive(config, logger, "fallback.trigger.sustained-seconds", 3);
        positive(config, logger, "fallback.recovery.sustained-seconds", 10);
        positive(config, logger, "alerts.cooldown-seconds", 60);

        // Recovery stage delays, in seconds. A non-positive delay would schedule the stage for
        // the same tick recovery began, collapsing the staged resume the guard exists to do:
        // every tracker, module and deep analysis would come back at once on a server that has
        // only just stopped struggling.
        positive(config, logger, "fallback.recovery-stages.stage-2-delay", 5);
        positive(config, logger, "fallback.recovery-stages.stage-3-delay", 10);
        positive(config, logger, "fallback.recovery-stages.stage-4-delay", 15);
        positive(config, logger, "fallback.recovery-stages.stage-5-delay", 20);
        positive(config, logger, "fallback.recovery-stages.stage-6-delay", 30);

        // Guard state thresholds. These drive the state machine and, unlike the alerting ladder
        // above, were not validated at all - a TPS threshold of 25 here silently pinned the guard
        // in that state on a healthy server.
        tps(config, logger, "performance-guard.states.warning.tps", 18.0);
        tps(config, logger, "performance-guard.states.critical.tps", 15.0);
        tps(config, logger, "performance-guard.states.fallback.tps", 10.0);
        tps(config, logger, "performance-guard.states.recovery.tps", 18.0);

        positive(config, logger, "performance-guard.states.warning.mspt", 55.0);
        positive(config, logger, "performance-guard.states.critical.mspt", 70.0);

        positive(config, logger, "performance-guard.states.warning.sustained-seconds", 10);
        positive(config, logger, "performance-guard.states.critical.sustained-seconds", 5);
        positive(config, logger, "performance-guard.states.fallback.sustained-seconds", 3);
        positive(config, logger, "performance-guard.states.recovery.sustained-seconds", 10);

        // Anti-flap controls. Zero is allowed and meaningful here - it means "no margin" and
        // "no minimum dwell time" - so only negatives are corrected.
        nonNegative(config, logger, "performance-guard.stability.tps-hysteresis", 1.0);
        nonNegative(config, logger, "performance-guard.stability.mspt-hysteresis", 5.0);
        nonNegative(config, logger, "performance-guard.stability.de-escalation-sustained-seconds", 10);
        nonNegative(config, logger, "performance-guard.stability.min-state-seconds", 5);

        // Self-monitoring budgets, in milliseconds. A budget of zero or less is exceeded by every
        // execution, so with suspend-on-budget-exceeded on (the default) it suspends every module
        // it names after violations-before-suspend cycles.
        positive(config, logger, "self-monitoring.budget.health-monitor", 5.0);
        positive(config, logger, "self-monitoring.budget.metrics-collection", 5.0);
        positive(config, logger, "self-monitoring.budget.deep-analysis", 10.0);
        positive(config, logger, "self-monitoring.budget.optimization-module", 5.0);
        positive(config, logger, "self-monitoring.violations-before-suspend", 3);

        // Console log cooldown. Unlike the durations above, zero is NOT honoured: there is no
        // setting for "print an identical line every monitoring cycle", so 0 resolves to the
        // default and anything under the floor is raised to it. See ConfigRules.
        logCooldown(config, logger, "self-monitoring.logging.cooldown-seconds",
                SelfMonitorLogging.DEFAULT_COOLDOWN_SECONDS);

        // Diagnostics sampling. interval-ticks at zero would ask the scheduler to sample every
        // tick; a non-positive chunk budget would stop the per-chunk pass making progress at all,
        // and it is the pass that resumes where it stopped, so it would never cover any chunk.
        positive(config, logger, "diagnostics.sampling.interval-ticks", 1200);
        positive(config, logger, "diagnostics.sampling.chunk-scan-budget", 2000);
        positive(config, logger, "diagnostics.sampling.stale-after-seconds", 30);

        // On-demand plugin diagnostic. The durations are safety settings - a profiling session
        // replaces other plugins' listener registrations for as long as it runs - so the range
        // check here is only the first of two gates: a max-duration outside the compiled
        // [3, 60] window is corrected to the documented default of 30 and logged, and whatever
        // survives is clamped again by PluginScanSettings' constructor. That second gate is the
        // one that matters, because it holds even for a config.yml that never reached this
        // validator, and it is why no value on disk can produce an unbounded session.
        positive(config, logger, "diagnostics.plugin-scan.default-duration-seconds", 10);
        apply(config, logger, ConfigRules.inRange(
                "diagnostics.plugin-scan.max-duration-seconds",
                config.getInt("diagnostics.plugin-scan.max-duration-seconds", 30), 30,
                PluginScanSettings.MIN_DURATION_SECONDS,
                PluginScanSettings.HARD_MAX_DURATION_SECONDS));
        positive(config, logger, "diagnostics.plugin-scan.top-results", 10);
        positive(config, logger, "diagnostics.plugin-scan.progress-interval-seconds", 5);
    }

    private static void positive(FileConfiguration config, Logger logger, String path,
                                 int defaultValue) {
        apply(config, logger,
                ConfigRules.positive(path, config.getInt(path, defaultValue), defaultValue));
    }

    private static void positive(FileConfiguration config, Logger logger, String path,
                                 double defaultValue) {
        apply(config, logger,
                ConfigRules.positive(path, config.getDouble(path, defaultValue), defaultValue));
    }

    private static void tps(FileConfiguration config, Logger logger, String path,
                            double defaultValue) {
        apply(config, logger,
                ConfigRules.tpsThreshold(path, config.getDouble(path, defaultValue), defaultValue));
    }

    private static void nonNegative(FileConfiguration config, Logger logger, String path,
                                    int defaultValue) {
        apply(config, logger,
                ConfigRules.nonNegative(path, config.getInt(path, defaultValue), defaultValue));
    }

    private static void nonNegative(FileConfiguration config, Logger logger, String path,
                                    double defaultValue) {
        apply(config, logger,
                ConfigRules.nonNegative(path, config.getDouble(path, defaultValue), defaultValue));
    }

    private static void logCooldown(FileConfiguration config, Logger logger, String path,
                                    int defaultSeconds) {
        apply(config, logger, ConfigRules.logCooldownSeconds(
                path, config.getInt(path, defaultSeconds), defaultSeconds));
    }

    private static void apply(FileConfiguration config, Logger logger, ConfigValue result) {
        if (result.isValid()) {
            return;
        }
        logger.warning(result.message());
        config.set(result.path(), result.replacement());
    }
}
