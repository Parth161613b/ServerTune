package com.servertune.guard;

import com.servertune.fallback.FallbackState;

/**
 * The performance state machine: NORMAL, WARNING, CRITICAL, FALLBACK, RECOVERING.
 *
 * <p>Pure logic, no Bukkit types, and the clock is supplied by the caller, so the whole
 * transition table can be driven deterministically from a unit test.
 *
 * <p>Both TPS and MSPT feed the decision. TPS is a lagging average, so a server that has
 * just started to struggle shows a high MSPT several seconds before TPS moves; treating a
 * bad MSPT as equivalent to a bad TPS gets the guard reacting on the earlier signal.
 *
 * <h2>Why this does not thrash</h2>
 * Four rules, all of which have to pass before the state actually changes:
 * <ol>
 *   <li><b>Sustained</b> - a candidate state must hold continuously for the configured
 *       duration for that level. One bad sample changes nothing.</li>
 *   <li><b>Hysteresis</b> - improving out of a state needs the metric to clear the
 *       threshold by a margin, so a value sitting exactly on the line cannot flip back and
 *       forth.</li>
 *   <li><b>Minimum dwell</b> - two state changes may not happen closer together than
 *       {@code minStateSeconds}.</li>
 *   <li><b>Escalation priority</b> - rules 3 is waived when escalating into FALLBACK.
 *       Protecting a dying server must never wait on an anti-flapping timer.</li>
 * </ol>
 *
 * <p>RECOVERING is not exited by this class on its own. The owner of the machine runs the
 * staged recovery and calls {@link #completeRecovery(long)} when the last stage finishes;
 * until then the only automatic transition out of RECOVERING is back into FALLBACK if the
 * server relapses.
 */
public class PerformanceStateMachine {

    private GuardThresholds thresholds;

    private FallbackState currentState = FallbackState.NORMAL;
    private long currentStateSince;
    private long lastTransitionAt;

    /** The state the metrics are currently asking for, and when it was first asked for. */
    private FallbackState candidate = null;
    private long candidateSince = 0L;

    private int transitionCount = 0;
    private StateTransition lastTransition = null;

    public PerformanceStateMachine(GuardThresholds thresholds, long startMillis) {
        this.thresholds = thresholds;
        this.currentStateSince = startMillis;
        this.lastTransitionAt = startMillis;
    }

    /**
     * Feed one health sample.
     *
     * @return the transition that happened, or null if the state did not change
     */
    public StateTransition evaluate(double tps, double mspt, long nowMillis) {
        return switch (currentState) {
            case FALLBACK -> evaluateFallback(tps, mspt, nowMillis);
            case RECOVERING -> evaluateRecovering(tps, mspt, nowMillis);
            default -> evaluateActive(tps, mspt, nowMillis);
        };
    }

    /** NORMAL, WARNING and CRITICAL all share the same escalate/de-escalate logic. */
    private StateTransition evaluateActive(double tps, double mspt, long nowMillis) {
        FallbackState desired = classify(tps, mspt, 0.0, 0.0);

        // De-escalating? Re-classify with the hysteresis margin applied. If the relaxed
        // classification still says we belong where we are, the metrics are only hovering.
        if (rank(desired) < rank(currentState)) {
            desired = classify(tps, mspt, thresholds.getTpsHysteresis(), thresholds.getMsptHysteresis());
            if (rank(desired) >= rank(currentState)) {
                clearCandidate();
                return null;
            }
        }

        if (desired == currentState) {
            clearCandidate();
            return null;
        }

        long held = trackCandidate(desired, nowMillis);
        boolean escalating = rank(desired) > rank(currentState);
        long required = escalating
                ? sustainedFor(desired)
                : thresholds.getDeEscalationSustainedMillis();

        if (held < required) {
            return null;
        }

        // Anti-flap dwell, waived only for the fallback safety path.
        if (desired != FallbackState.FALLBACK
                && nowMillis - lastTransitionAt < thresholds.getMinStateMillis()) {
            return null;
        }

        String reason = String.format("TPS %.2f, MSPT %.2fms sustained %.1fs",
                tps, mspt, held / 1000.0);
        return transition(desired, reason, nowMillis);
    }

    private StateTransition evaluateFallback(double tps, double mspt, long nowMillis) {
        boolean recovered = tps >= thresholds.getRecoveryTps() && mspt <= thresholds.getWarningMspt();

        if (!recovered) {
            clearCandidate();
            return null;
        }

        long held = trackCandidate(FallbackState.RECOVERING, nowMillis);
        if (held < thresholds.getRecoverySustainedMillis()) {
            return null;
        }

        String reason = String.format("TPS %.2f held above %.1f for %.1fs",
                tps, thresholds.getRecoveryTps(), held / 1000.0);
        return transition(FallbackState.RECOVERING, reason, nowMillis);
    }

    private StateTransition evaluateRecovering(double tps, double mspt, long nowMillis) {
        // A relapse aborts recovery immediately once sustained; nothing else pulls us out.
        if (tps >= thresholds.getFallbackTps()) {
            clearCandidate();
            return null;
        }

        long held = trackCandidate(FallbackState.FALLBACK, nowMillis);
        if (held < thresholds.getFallbackSustainedMillis()) {
            return null;
        }

        String reason = String.format("relapsed to TPS %.2f during recovery", tps);
        return transition(FallbackState.FALLBACK, reason, nowMillis);
    }

    /**
     * Called by the guard when the last staged-recovery step finishes. Leaves RECOVERING
     * for NORMAL; the next {@link #evaluate} re-derives the real level from live metrics.
     */
    public StateTransition completeRecovery(long nowMillis) {
        if (currentState != FallbackState.RECOVERING) {
            return null;
        }
        return transition(FallbackState.NORMAL, "staged recovery complete", nowMillis);
    }

    /**
     * Classify raw metrics into a level.
     *
     * @param tpsMargin  added to every TPS threshold (used for de-escalation hysteresis)
     * @param msptMargin subtracted from every MSPT threshold
     */
    private FallbackState classify(double tps, double mspt, double tpsMargin, double msptMargin) {
        if (tps < thresholds.getFallbackTps() + tpsMargin) {
            return FallbackState.FALLBACK;
        }
        if (tps < thresholds.getCriticalTps() + tpsMargin
                || mspt > thresholds.getCriticalMspt() - msptMargin) {
            return FallbackState.CRITICAL;
        }
        if (tps < thresholds.getWarningTps() + tpsMargin
                || mspt > thresholds.getWarningMspt() - msptMargin) {
            return FallbackState.WARNING;
        }
        return FallbackState.NORMAL;
    }

    private long sustainedFor(FallbackState state) {
        return switch (state) {
            case WARNING -> thresholds.getWarningSustainedMillis();
            case CRITICAL -> thresholds.getCriticalSustainedMillis();
            case FALLBACK -> thresholds.getFallbackSustainedMillis();
            case RECOVERING -> thresholds.getRecoverySustainedMillis();
            case NORMAL -> thresholds.getDeEscalationSustainedMillis();
        };
    }

    /** Returns how long {@code desired} has been continuously requested, in millis. */
    private long trackCandidate(FallbackState desired, long nowMillis) {
        if (candidate != desired) {
            candidate = desired;
            candidateSince = nowMillis;
        }
        return nowMillis - candidateSince;
    }

    private void clearCandidate() {
        candidate = null;
        candidateSince = 0L;
    }

    private StateTransition transition(FallbackState to, String reason, long nowMillis) {
        StateTransition t = new StateTransition(currentState, to, reason, nowMillis);
        currentState = to;
        currentStateSince = nowMillis;
        lastTransitionAt = nowMillis;
        transitionCount++;
        lastTransition = t;
        clearCandidate();
        return t;
    }

    /** Applies new thresholds on /optimizer reload without losing the current state. */
    public void updateThresholds(GuardThresholds thresholds) {
        this.thresholds = thresholds;
        clearCandidate();
    }

    public GuardThresholds getThresholds() {
        return thresholds;
    }

    public FallbackState getCurrentState() {
        return currentState;
    }

    public FallbackState getCandidateState() {
        return candidate;
    }

    public long getCurrentStateSince() {
        return currentStateSince;
    }

    public long getTimeInState(long nowMillis) {
        return nowMillis - currentStateSince;
    }

    public int getTransitionCount() {
        return transitionCount;
    }

    public StateTransition getLastTransition() {
        return lastTransition;
    }

    /** Ordering used to tell escalation from de-escalation. RECOVERING sits below FALLBACK. */
    public static int rank(FallbackState state) {
        return switch (state) {
            case NORMAL -> 0;
            case WARNING -> 1;
            case RECOVERING -> 2;
            case CRITICAL -> 3;
            case FALLBACK -> 4;
        };
    }

    public record StateTransition(FallbackState from, FallbackState to, String reason, long atMillis) {

        public boolean isEscalation() {
            return rank(to) > rank(from);
        }

        public String describe() {
            return from + " -> " + to + " (" + reason + ")";
        }
    }
}
