package com.servertune.guard;

/**
 * Every threshold the performance guard uses, in one immutable value object.
 *
 * <p>Deliberately free of Bukkit types so the state machine can be unit tested without a
 * running server. {@link PerformanceGuard} builds one of these from config.yml.
 *
 * <p>Two mechanisms exist purely to stop the guard thrashing between states:
 * <ul>
 *   <li>hysteresis - de-escalating requires the metric to clear the threshold by a margin,
 *       so a value hovering exactly on the line does not flip the state every sample</li>
 *   <li>minimum state duration - no two state changes may happen closer together than this,
 *       with escalation into FALLBACK exempt because that is the safety path</li>
 * </ul>
 */
public final class GuardThresholds {

    private final double warningTps;
    private final double criticalTps;
    private final double fallbackTps;
    private final double recoveryTps;
    private final double warningMspt;
    private final double criticalMspt;
    private final int warningSustainedSeconds;
    private final int criticalSustainedSeconds;
    private final int fallbackSustainedSeconds;
    private final int recoverySustainedSeconds;
    private final int deEscalationSustainedSeconds;
    private final int minStateSeconds;
    private final double tpsHysteresis;
    private final double msptHysteresis;

    private GuardThresholds(Builder b) {
        this.warningTps = b.warningTps;
        this.criticalTps = b.criticalTps;
        this.fallbackTps = b.fallbackTps;
        this.recoveryTps = b.recoveryTps;
        this.warningMspt = b.warningMspt;
        this.criticalMspt = b.criticalMspt;
        this.warningSustainedSeconds = b.warningSustainedSeconds;
        this.criticalSustainedSeconds = b.criticalSustainedSeconds;
        this.fallbackSustainedSeconds = b.fallbackSustainedSeconds;
        this.recoverySustainedSeconds = b.recoverySustainedSeconds;
        this.deEscalationSustainedSeconds = b.deEscalationSustainedSeconds;
        this.minStateSeconds = b.minStateSeconds;
        this.tpsHysteresis = b.tpsHysteresis;
        this.msptHysteresis = b.msptHysteresis;
    }

    public double getWarningTps() {
        return warningTps;
    }

    public double getCriticalTps() {
        return criticalTps;
    }

    public double getFallbackTps() {
        return fallbackTps;
    }

    public double getRecoveryTps() {
        return recoveryTps;
    }

    public double getWarningMspt() {
        return warningMspt;
    }

    public double getCriticalMspt() {
        return criticalMspt;
    }

    public long getWarningSustainedMillis() {
        return warningSustainedSeconds * 1000L;
    }

    public long getCriticalSustainedMillis() {
        return criticalSustainedSeconds * 1000L;
    }

    public long getFallbackSustainedMillis() {
        return fallbackSustainedSeconds * 1000L;
    }

    public long getRecoverySustainedMillis() {
        return recoverySustainedSeconds * 1000L;
    }

    public long getDeEscalationSustainedMillis() {
        return deEscalationSustainedSeconds * 1000L;
    }

    public long getMinStateMillis() {
        return minStateSeconds * 1000L;
    }

    public double getTpsHysteresis() {
        return tpsHysteresis;
    }

    public double getMsptHysteresis() {
        return msptHysteresis;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Config defaults, matching the shipped config.yml. */
    public static GuardThresholds defaults() {
        return builder().build();
    }

    public static final class Builder {
        private double warningTps = 18.0;
        private double criticalTps = 15.0;
        private double fallbackTps = 10.0;
        private double recoveryTps = 18.0;
        // 50ms IS the tick budget: MSPT 50 is exactly 20 TPS. So a state threshold has to sit
        // meaningfully above 50 or every server below a perfect 20 TPS reads as degraded.
        // These mirror the TPS ladder: 1000/18 = 55.6ms, 1000/15 = 66.7ms.
        private double warningMspt = 55.0;
        private double criticalMspt = 70.0;
        private int warningSustainedSeconds = 10;
        private int criticalSustainedSeconds = 5;
        private int fallbackSustainedSeconds = 3;
        private int recoverySustainedSeconds = 10;
        private int deEscalationSustainedSeconds = 10;
        private int minStateSeconds = 5;
        private double tpsHysteresis = 1.0;
        private double msptHysteresis = 5.0;

        public Builder warningTps(double v) {
            this.warningTps = v;
            return this;
        }

        public Builder criticalTps(double v) {
            this.criticalTps = v;
            return this;
        }

        public Builder fallbackTps(double v) {
            this.fallbackTps = v;
            return this;
        }

        public Builder recoveryTps(double v) {
            this.recoveryTps = v;
            return this;
        }

        public Builder warningMspt(double v) {
            this.warningMspt = v;
            return this;
        }

        public Builder criticalMspt(double v) {
            this.criticalMspt = v;
            return this;
        }

        public Builder warningSustainedSeconds(int v) {
            this.warningSustainedSeconds = v;
            return this;
        }

        public Builder criticalSustainedSeconds(int v) {
            this.criticalSustainedSeconds = v;
            return this;
        }

        public Builder fallbackSustainedSeconds(int v) {
            this.fallbackSustainedSeconds = v;
            return this;
        }

        public Builder recoverySustainedSeconds(int v) {
            this.recoverySustainedSeconds = v;
            return this;
        }

        public Builder deEscalationSustainedSeconds(int v) {
            this.deEscalationSustainedSeconds = v;
            return this;
        }

        public Builder minStateSeconds(int v) {
            this.minStateSeconds = v;
            return this;
        }

        public Builder tpsHysteresis(double v) {
            this.tpsHysteresis = v;
            return this;
        }

        public Builder msptHysteresis(double v) {
            this.msptHysteresis = v;
            return this;
        }

        public GuardThresholds build() {
            return new GuardThresholds(this);
        }
    }
}
