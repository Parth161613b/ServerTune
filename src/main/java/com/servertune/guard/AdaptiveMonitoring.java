package com.servertune.guard;

import com.servertune.fallback.FallbackState;

/**
 * Scales the monitoring interval to the server's state (spec section 5).
 *
 * <p>The multipliers are applied to the configured base interval:
 * <ul>
 *   <li>NORMAL - 1.0x, the configured interval, minimal work</li>
 *   <li>WARNING - 0.75x, moderately more often</li>
 *   <li>CRITICAL - 0.5x, more frequent but only the cheap health sample</li>
 *   <li>FALLBACK - 1.5x, almost no optimizer work; just enough to notice recovery</li>
 *   <li>RECOVERING - 1.0x</li>
 * </ul>
 *
 * <h2>Why sampling faster under load is not a feedback loop</h2>
 * The only thing whose frequency changes is the health sample, which reads counters Paper
 * already maintains ({@code getTPS()}, {@code getAverageTickTime()}) plus a memory read.
 * That is microseconds, and the benchmark suite holds it there. Deep analysis, chunk
 * scanning and hotspot ranking - the parts that actually cost something - are never sped
 * up by this class; under CRITICAL and FALLBACK they slow down or stop. So the extra work
 * taken on in a bad state is bounded and tiny, while the work given up is large.
 *
 * <p>{@link #FLOOR_TICKS} is a hard lower bound so no combination of config values can
 * make the guard sample more than five times a second.
 */
public final class AdaptiveMonitoring {

    /** Never sample more often than every 4 ticks, whatever the config says. */
    public static final int FLOOR_TICKS = 4;

    /** Never let a state stretch the interval past 30 seconds. */
    public static final int CEILING_TICKS = 600;

    private final boolean enabled;
    private final int baseIntervalTicks;
    private final double normalMultiplier;
    private final double warningMultiplier;
    private final double criticalMultiplier;
    private final double fallbackMultiplier;
    private final double recoveringMultiplier;

    private AdaptiveMonitoring(Builder b) {
        this.enabled = b.enabled;
        this.baseIntervalTicks = b.baseIntervalTicks;
        this.normalMultiplier = b.normalMultiplier;
        this.warningMultiplier = b.warningMultiplier;
        this.criticalMultiplier = b.criticalMultiplier;
        this.fallbackMultiplier = b.fallbackMultiplier;
        this.recoveringMultiplier = b.recoveringMultiplier;
    }

    /** The health-monitor interval to use in {@code state}, in ticks. */
    public int intervalFor(FallbackState state) {
        if (!enabled) {
            return clamp(baseIntervalTicks);
        }
        return clamp((int) Math.round(baseIntervalTicks * multiplierFor(state)));
    }

    public double multiplierFor(FallbackState state) {
        return switch (state) {
            case NORMAL -> normalMultiplier;
            case WARNING -> warningMultiplier;
            case CRITICAL -> criticalMultiplier;
            case FALLBACK -> fallbackMultiplier;
            case RECOVERING -> recoveringMultiplier;
        };
    }

    /** True when the interval genuinely differs, so the guard can skip a needless reschedule. */
    public boolean requiresReschedule(FallbackState from, FallbackState to) {
        return intervalFor(from) != intervalFor(to);
    }

    private static int clamp(int ticks) {
        return Math.max(FLOOR_TICKS, Math.min(CEILING_TICKS, ticks));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getBaseIntervalTicks() {
        return baseIntervalTicks;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AdaptiveMonitoring defaults(int baseIntervalTicks) {
        return builder().baseIntervalTicks(baseIntervalTicks).build();
    }

    public static final class Builder {
        private boolean enabled = true;
        private int baseIntervalTicks = 100;
        private double normalMultiplier = 1.0;
        private double warningMultiplier = 0.75;
        private double criticalMultiplier = 0.5;
        private double fallbackMultiplier = 1.5;
        private double recoveringMultiplier = 1.0;

        public Builder enabled(boolean v) {
            this.enabled = v;
            return this;
        }

        public Builder baseIntervalTicks(int v) {
            this.baseIntervalTicks = v;
            return this;
        }

        public Builder normalMultiplier(double v) {
            this.normalMultiplier = v;
            return this;
        }

        public Builder warningMultiplier(double v) {
            this.warningMultiplier = v;
            return this;
        }

        public Builder criticalMultiplier(double v) {
            this.criticalMultiplier = v;
            return this;
        }

        public Builder fallbackMultiplier(double v) {
            this.fallbackMultiplier = v;
            return this;
        }

        public Builder recoveringMultiplier(double v) {
            this.recoveringMultiplier = v;
            return this;
        }

        public AdaptiveMonitoring build() {
            return new AdaptiveMonitoring(this);
        }
    }
}
