package com.servertune.config;

/**
 * Scheduling intervals per profile, in ticks.
 *
 * <p>Only the two intervals the plugin actually schedules on are held here. Entity and chunk
 * tracking are event-driven, so they never had an interval to carry.
 */
public enum PerformanceProfile {
    LIGHT(200, 2400),
    BALANCED(100, 1200),
    AGGRESSIVE(60, 600),
    CUSTOM(100, 1200); // Default values, overridden by config

    private final int healthInterval;
    private final int deepAnalysisInterval;

    PerformanceProfile(int healthInterval, int deepAnalysisInterval) {
        this.healthInterval = healthInterval;
        this.deepAnalysisInterval = deepAnalysisInterval;
    }

    public int getHealthInterval() {
        return healthInterval;
    }

    public int getDeepAnalysisInterval() {
        return deepAnalysisInterval;
    }

    public static PerformanceProfile fromString(String name) {
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BALANCED;
        }
    }
}
