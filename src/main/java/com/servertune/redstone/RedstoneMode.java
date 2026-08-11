package com.servertune.redstone;

/**
 * Redstone optimization modes.
 *
 * <p>Each mode is a set of defaults. Every value a mode supplies can be overridden in
 * config under {@code optimization.modules.redstone.custom}, and CUSTOM reads all of
 * its values from there.
 *
 * <p>Gameplay consequences escalate with each mode. VANILLA and CONSERVATIVE never
 * cancel anything.
 */
public enum RedstoneMode {

    /**
     * Monitoring disabled entirely. No listeners do work, nothing is ever cancelled.
     * Zero gameplay impact, zero overhead.
     */
    VANILLA(false, -1, -1, 0, false, -1),

    /**
     * Monitoring and hotspot detection only. Nothing is cancelled.
     * Zero gameplay impact. This is the default.
     */
    CONSERVATIVE(true, -1, -1, 0, false, -1),

    /**
     * Limits only genuinely extreme activity. Thresholds are set high enough that
     * ordinary redstone contraptions are unaffected.
     *
     * <p>GAMEPLAY IMPACT: very large piston arrays and rapid dispenser farms may be
     * throttled. Normal doors, lamps and small farms are not.
     */
    BALANCED(true, 40, 120, 0, true, 30),

    /**
     * Aggressively limits piston and dispenser activity.
     *
     * <p>GAMEPLAY IMPACT: WILL BREAK technical farms. Flying machines stall, fast
     * clocks are cut, TNT duplicators and rapid item farms stop working. This is
     * intentional and must be enabled deliberately by the server owner.
     */
    AGGRESSIVE(true, 10, 30, 2, true, 8),

    /**
     * Every value read from the custom config section.
     */
    CUSTOM(true, -1, -1, 0, false, -1);

    private final boolean monitoringEnabled;
    private final int pistonPerChunkPerSecond;
    private final int pistonPerChunkPerWindow;
    private final int pistonCooldownTicks;
    private final boolean dispenserLimitEnabled;
    private final int dispenserPerChunkPerSecond;

    RedstoneMode(boolean monitoringEnabled,
                 int pistonPerChunkPerSecond,
                 int pistonPerChunkPerWindow,
                 int pistonCooldownTicks,
                 boolean dispenserLimitEnabled,
                 int dispenserPerChunkPerSecond) {
        this.monitoringEnabled = monitoringEnabled;
        this.pistonPerChunkPerSecond = pistonPerChunkPerSecond;
        this.pistonPerChunkPerWindow = pistonPerChunkPerWindow;
        this.pistonCooldownTicks = pistonCooldownTicks;
        this.dispenserLimitEnabled = dispenserLimitEnabled;
        this.dispenserPerChunkPerSecond = dispenserPerChunkPerSecond;
    }

    public boolean isMonitoringEnabled() {
        return monitoringEnabled;
    }

    /** -1 means no limit. */
    public int getPistonPerChunkPerSecond() {
        return pistonPerChunkPerSecond;
    }

    public int getPistonPerChunkPerWindow() {
        return pistonPerChunkPerWindow;
    }

    public int getPistonCooldownTicks() {
        return pistonCooldownTicks;
    }

    public boolean isDispenserLimitEnabled() {
        return dispenserLimitEnabled;
    }

    public int getDispenserPerChunkPerSecond() {
        return dispenserPerChunkPerSecond;
    }

    /** True when this mode can cancel player-visible redstone behaviour. */
    public boolean altersGameplay() {
        return this == BALANCED || this == AGGRESSIVE || this == CUSTOM;
    }

    public static RedstoneMode fromString(String value, RedstoneMode fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
