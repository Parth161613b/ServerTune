package com.servertune.selfmonitor;

import com.servertune.config.ConfigRules;

/**
 * What self-monitoring is allowed to print to the console, and how often.
 *
 * <p>Measurement is not configurable and is not described here. Every execution is timed, every
 * overrun is counted, and an over-budget module is still suspended after its configured streak
 * whatever these settings say - {@link BudgetWarningPolicy} gates the console and nothing else.
 * The distinction matters because the console defaults are deliberately silent.
 *
 * <p><b>Defaults are production defaults.</b> {@link #DEFAULT_ENABLED} is {@code false}: a fresh
 * install prints nothing about its own budgets. The information is still there in
 * {@code /serverhealth debug}, which reads the same recorded state without measuring anything new.
 * Recovery lines default off separately, because they are only of interest to somebody already
 * watching the warnings.
 *
 * <p>Bukkit-free, so the defaults and the cooldown rule are reachable from a unit test -
 * paper-api is {@code compileOnly}, so anything holding a Bukkit type is not.
 */
public record SelfMonitorLogging(boolean enabled,
                                 boolean overBudget,
                                 boolean recovery,
                                 boolean verbose,
                                 long cooldownMillis) {

    /** Console logging is off on a fresh install. See the class note. */
    public static final boolean DEFAULT_ENABLED = false;

    /** When logging is switched on, over-budget warnings are the thing you switched it on for. */
    public static final boolean DEFAULT_OVER_BUDGET = true;

    /** Recovery lines are opt-in even with logging on. */
    public static final boolean DEFAULT_RECOVERY = false;

    /** Verbose adds detail to lines already being printed; it never adds lines. */
    public static final boolean DEFAULT_VERBOSE = false;

    /** Seconds between repeated warnings for one subsystem. See {@link ConfigRules}. */
    public static final int DEFAULT_COOLDOWN_SECONDS = 60;

    public SelfMonitorLogging {
        cooldownMillis = Math.max(0L, cooldownMillis);
    }

    /** The shipped defaults, independent of any config file. */
    public static SelfMonitorLogging defaults() {
        return new SelfMonitorLogging(DEFAULT_ENABLED, DEFAULT_OVER_BUDGET, DEFAULT_RECOVERY,
                DEFAULT_VERBOSE, DEFAULT_COOLDOWN_SECONDS * 1000L);
    }

    /**
     * Builds settings from raw config values, applying the cooldown rule in
     * {@link ConfigRules#resolveLogCooldownSeconds}. The validator applies the same rule when it
     * reads config.yml, so this is the second of two places that agree rather than a second
     * opinion - both call the one function.
     */
    public static SelfMonitorLogging of(boolean enabled, boolean overBudget, boolean recovery,
                                        boolean verbose, int rawCooldownSeconds) {
        int seconds = ConfigRules.resolveLogCooldownSeconds(rawCooldownSeconds,
                DEFAULT_COOLDOWN_SECONDS);
        return new SelfMonitorLogging(enabled, overBudget, recovery, verbose, seconds * 1000L);
    }

    public long cooldownSeconds() {
        return cooldownMillis / 1000L;
    }
}
