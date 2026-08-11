package com.servertune.guard;

import com.servertune.fallback.FallbackState;

import java.util.List;
import java.util.Map;

/**
 * What the guard is permitted to do in each state (spec section 7).
 *
 * <p>The central rule: <b>alerts and automatic optimization are separate switches</b>. An
 * owner running {@code alerts: true} with {@code automatic-optimization: false} gets the
 * full state machine, every notification and every log line, and the guard changes nothing
 * about their server. That is the default, because silently enabling optimization modules
 * during a lag spike is a surprising thing for a monitoring plugin to do.
 *
 * <p>FALLBACK is the one exception and it is deliberate: suspending the optimizer's own
 * work is not an optimization, it is the plugin getting out of the way. It happens even
 * with automatic optimization off, because the alternative is a struggling server still
 * paying for optimizer overhead.
 *
 * <p>Bukkit-free so it can be constructed directly in tests.
 */
public final class GuardActionPolicy {

    private final boolean alertsEnabled;
    private final boolean automaticOptimizationEnabled;
    private final String alertPermission;
    private final boolean logToConsole;
    private final Map<FallbackState, StateActions> perState;
    private final boolean cancelExpensiveTasksInFallback;
    private final boolean stopDeepAnalysisInFallback;

    private GuardActionPolicy(Builder b) {
        this.alertsEnabled = b.alertsEnabled;
        this.automaticOptimizationEnabled = b.automaticOptimizationEnabled;
        this.alertPermission = b.alertPermission;
        this.logToConsole = b.logToConsole;
        this.perState = Map.copyOf(b.perState);
        this.cancelExpensiveTasksInFallback = b.cancelExpensiveTasksInFallback;
        this.stopDeepAnalysisInFallback = b.stopDeepAnalysisInFallback;
    }

    /** True when staff should be told about entering {@code state}. */
    public boolean shouldNotify(FallbackState state) {
        return alertsEnabled && actionsFor(state).notifyStaff();
    }

    /**
     * Modules the owner wants switched on in {@code state}. Empty unless automatic
     * optimization is explicitly enabled - the alerts switch never implies changes.
     */
    public List<String> modulesToEnable(FallbackState state) {
        if (!automaticOptimizationEnabled) {
            return List.of();
        }
        return actionsFor(state).enableModules();
    }

    /** Modules the owner wants switched off in {@code state}. Same gating as above. */
    public List<String> modulesToSuspend(FallbackState state) {
        if (!automaticOptimizationEnabled) {
            return List.of();
        }
        return actionsFor(state).suspendModules();
    }

    /**
     * Whether the guard may suspend the optimizer's own modules on entering FALLBACK.
     * Not gated on automatic optimization - see the class javadoc.
     */
    public boolean shouldSuspendOptimizerInFallback() {
        return true;
    }

    public boolean shouldCancelExpensiveTasksInFallback() {
        return cancelExpensiveTasksInFallback;
    }

    public boolean shouldStopDeepAnalysisInFallback() {
        return stopDeepAnalysisInFallback;
    }

    public boolean isAlertsEnabled() {
        return alertsEnabled;
    }

    public boolean isAutomaticOptimizationEnabled() {
        return automaticOptimizationEnabled;
    }

    public String getAlertPermission() {
        return alertPermission;
    }

    public boolean isLogToConsole() {
        return logToConsole;
    }

    public StateActions actionsFor(FallbackState state) {
        return perState.getOrDefault(state, StateActions.none());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Conservative default: alerts on, automatic optimization off. */
    public static GuardActionPolicy defaults() {
        return builder()
                .stateActions(FallbackState.WARNING, StateActions.notifyOnly())
                .stateActions(FallbackState.CRITICAL, StateActions.notifyOnly())
                .stateActions(FallbackState.FALLBACK, StateActions.notifyOnly())
                .stateActions(FallbackState.RECOVERING, StateActions.notifyOnly())
                .build();
    }

    /** The owner-configured action set for one state. */
    public record StateActions(boolean notifyStaff, List<String> enableModules, List<String> suspendModules) {

        public StateActions {
            enableModules = List.copyOf(enableModules);
            suspendModules = List.copyOf(suspendModules);
        }

        public static StateActions none() {
            return new StateActions(false, List.of(), List.of());
        }

        public static StateActions notifyOnly() {
            return new StateActions(true, List.of(), List.of());
        }
    }

    public static final class Builder {
        private boolean alertsEnabled = true;
        private boolean automaticOptimizationEnabled = false;
        private String alertPermission = "serverhealth.alert";
        private boolean logToConsole = true;
        private final Map<FallbackState, StateActions> perState = new java.util.EnumMap<>(FallbackState.class);
        private boolean cancelExpensiveTasksInFallback = true;
        private boolean stopDeepAnalysisInFallback = true;

        public Builder alertsEnabled(boolean v) {
            this.alertsEnabled = v;
            return this;
        }

        public Builder automaticOptimizationEnabled(boolean v) {
            this.automaticOptimizationEnabled = v;
            return this;
        }

        public Builder alertPermission(String v) {
            this.alertPermission = v;
            return this;
        }

        public Builder logToConsole(boolean v) {
            this.logToConsole = v;
            return this;
        }

        public Builder stateActions(FallbackState state, StateActions actions) {
            this.perState.put(state, actions);
            return this;
        }

        public Builder cancelExpensiveTasksInFallback(boolean v) {
            this.cancelExpensiveTasksInFallback = v;
            return this;
        }

        public Builder stopDeepAnalysisInFallback(boolean v) {
            this.stopDeepAnalysisInFallback = v;
            return this;
        }

        public GuardActionPolicy build() {
            return new GuardActionPolicy(this);
        }
    }
}
