package com.servertune.guard;

import com.servertune.fallback.FallbackState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole ladder in one run: NORMAL to WARNING to CRITICAL to FALLBACK, then back out through
 * RECOVERING to NORMAL.
 *
 * <p>{@link PerformanceStateMachineTest} covers each rung's rules in isolation and
 * {@link FallbackAtLowTpsTest} covers what happens once FALLBACK is reached. What this file
 * pins is the sequence - that a server degrading gradually visits every rung in order rather
 * than jumping, and that the way back up is the staged one.
 *
 * <p>{@link PerformanceGuard} needs a live server, so the assertions here are against the state
 * machine and the action policy the guard reads. The guard is a thin translation of both.
 */
class StateLadderTest {

    /** Comfortably healthy: above every warning threshold, well under warning MSPT. */
    private static final double HEALTHY_TPS = 20.0;
    private static final double HEALTHY_MSPT = 20.0;

    /** Below warningTps 18.0, above criticalTps 15.0. */
    private static final double DEGRADED_TPS = 17.0;
    private static final double DEGRADED_MSPT = 58.0;

    /** Below criticalTps 15.0, above fallbackTps 10.0. */
    private static final double STRUGGLING_TPS = 13.0;
    private static final double STRUGGLING_MSPT = 75.0;

    /** Below fallbackTps 10.0. */
    private static final double CRASHING_TPS = 8.0;
    private static final double CRASHING_MSPT = 125.0;

    private long clock = 1_000_000L;

    private final List<PerformanceStateMachine.StateTransition> transitions = new ArrayList<>();

    /** Feeds one metric for {@code seconds}, recording every transition seen. */
    private void hold(PerformanceStateMachine machine, double tps, double mspt, int seconds) {
        for (int i = 0; i < seconds; i++) {
            clock += 1000L;
            PerformanceStateMachine.StateTransition t = machine.evaluate(tps, mspt, clock);
            if (t != null) {
                transitions.add(t);
            }
        }
    }

    private PerformanceStateMachine machine() {
        return new PerformanceStateMachine(GuardThresholds.defaults(), clock);
    }

    private List<FallbackState> statesVisited() {
        List<FallbackState> visited = new ArrayList<>();
        for (PerformanceStateMachine.StateTransition t : transitions) {
            visited.add(t.to());
        }
        return visited;
    }

    @Test
    void aServerDegradingGraduallyClimbsEveryRungInOrder() {
        PerformanceStateMachine machine = machine();

        assertSame(FallbackState.NORMAL, machine.getCurrentState(),
                "a fresh machine starts healthy");

        // Each hold is generous enough to clear both the sustained window for that rung and
        // the 5s minimum dwell from the previous transition.
        hold(machine, DEGRADED_TPS, DEGRADED_MSPT, 12);
        assertSame(FallbackState.WARNING, machine.getCurrentState());

        hold(machine, STRUGGLING_TPS, STRUGGLING_MSPT, 8);
        assertSame(FallbackState.CRITICAL, machine.getCurrentState());

        hold(machine, CRASHING_TPS, CRASHING_MSPT, 5);
        assertSame(FallbackState.FALLBACK, machine.getCurrentState());

        assertEquals(
                List.of(FallbackState.WARNING, FallbackState.CRITICAL, FallbackState.FALLBACK),
                statesVisited(),
                "the ladder must be climbed rung by rung, with no state skipped or repeated");
    }

    @Test
    void theFullRoundTripEndsBackAtNormal() {
        PerformanceStateMachine machine = machine();

        hold(machine, DEGRADED_TPS, DEGRADED_MSPT, 12);
        hold(machine, STRUGGLING_TPS, STRUGGLING_MSPT, 8);
        hold(machine, CRASHING_TPS, CRASHING_MSPT, 5);
        assertSame(FallbackState.FALLBACK, machine.getCurrentState());

        // Recovery needs recoveryTps 18.0 held for 10s, and MSPT back under warning MSPT.
        hold(machine, HEALTHY_TPS, HEALTHY_MSPT, 11);
        assertSame(FallbackState.RECOVERING, machine.getCurrentState(),
                "sustained healthy metrics move FALLBACK to RECOVERING, not straight to NORMAL");

        // RECOVERING is left only when the guard's staged sequence finishes.
        PerformanceStateMachine.StateTransition done = machine.completeRecovery(clock);
        assertNotNull(done);
        assertSame(FallbackState.NORMAL, machine.getCurrentState());

        assertEquals(
                List.of(FallbackState.WARNING, FallbackState.CRITICAL, FallbackState.FALLBACK,
                        FallbackState.RECOVERING),
                statesVisited(),
                "every rung down and the staged route back up");
    }

    @Test
    void recoveryIsNeverAutomaticWhileTheStagesAreStillRunning() {
        PerformanceStateMachine machine = machine();
        hold(machine, CRASHING_TPS, CRASHING_MSPT, 5);
        hold(machine, HEALTHY_TPS, HEALTHY_MSPT, 11);
        assertSame(FallbackState.RECOVERING, machine.getCurrentState());

        // A perfectly healthy server, sampled for a long time, stays in RECOVERING. Only the
        // guard finishing its stages ends it - otherwise heavy work would resume all at once.
        hold(machine, HEALTHY_TPS, HEALTHY_MSPT, 60);
        assertSame(FallbackState.RECOVERING, machine.getCurrentState(),
                "nothing but completeRecovery() leaves RECOVERING upward");
    }

    @Test
    void aRelapseDuringRecoveryGoesStraightBackToFallback() {
        PerformanceStateMachine machine = machine();
        hold(machine, CRASHING_TPS, CRASHING_MSPT, 5);
        hold(machine, HEALTHY_TPS, HEALTHY_MSPT, 11);
        assertSame(FallbackState.RECOVERING, machine.getCurrentState());

        hold(machine, CRASHING_TPS, CRASHING_MSPT, 4);
        assertSame(FallbackState.FALLBACK, machine.getCurrentState(),
                "a server that collapses again mid-recovery must be protected immediately");
    }

    @Test
    void aCollapseFromNormalReachesFallbackWithoutWaitingOnTheRungsBelow() {
        PerformanceStateMachine machine = machine();

        // A sudden collapse - no gradual degradation. The fallback path is exempt from the
        // minimum-dwell rule precisely so protection is not delayed here.
        hold(machine, CRASHING_TPS, CRASHING_MSPT, 4);

        assertSame(FallbackState.FALLBACK, machine.getCurrentState());
        assertEquals(List.of(FallbackState.FALLBACK), statesVisited(),
                "protecting a dying server must not queue behind WARNING and CRITICAL");
    }

    @Test
    void everyRungOnTheWayDownIsAlsoVisited() {
        PerformanceStateMachine machine = machine();
        hold(machine, STRUGGLING_TPS, STRUGGLING_MSPT, 8);
        assertSame(FallbackState.CRITICAL, machine.getCurrentState());
        transitions.clear();

        // De-escalation needs the hysteresis margin cleared and the 10s de-escalation window.
        hold(machine, DEGRADED_TPS, DEGRADED_MSPT, 12);
        assertSame(FallbackState.WARNING, machine.getCurrentState());

        hold(machine, HEALTHY_TPS, HEALTHY_MSPT, 12);
        assertSame(FallbackState.NORMAL, machine.getCurrentState());

        assertEquals(List.of(FallbackState.WARNING, FallbackState.NORMAL), statesVisited(),
                "improving walks back down the ladder rather than jumping to NORMAL");
    }

    @Test
    void commandsAndAlertsSurviveEveryRungOfTheLadder() {
        // Section 4 requires commands to keep working throughout. No state in the policy
        // touches command registration, and alerts stay on in every degraded state.
        GuardActionPolicy policy = GuardActionPolicy.defaults();

        for (FallbackState state : FallbackState.values()) {
            assertTrue(policy.modulesToEnable(state).isEmpty(),
                    state + " must not switch modules on by default");
            assertTrue(policy.modulesToSuspend(state).isEmpty(),
                    state + " must not switch modules off by default");
        }

        assertFalse(policy.shouldNotify(FallbackState.NORMAL),
                "a healthy server should not be generating alerts");
        assertTrue(policy.shouldNotify(FallbackState.WARNING));
        assertTrue(policy.shouldNotify(FallbackState.CRITICAL));
        assertTrue(policy.shouldNotify(FallbackState.FALLBACK),
                "fallback is exactly when staff most need to be told");
        assertTrue(policy.shouldNotify(FallbackState.RECOVERING));
    }

    @Test
    void monitoringNeverStopsAtAnyRung() {
        AdaptiveMonitoring monitoring = AdaptiveMonitoring.defaults(100);

        for (FallbackState state : FallbackState.values()) {
            int interval = monitoring.intervalFor(state);
            assertTrue(interval >= AdaptiveMonitoring.FLOOR_TICKS,
                    state + " interval must respect the floor");
            assertTrue(interval <= AdaptiveMonitoring.CEILING_TICKS,
                    state + " must keep sampling at least every "
                            + (AdaptiveMonitoring.CEILING_TICKS / 20) + "s, or recovery is "
                            + "never noticed");
        }

        assertTrue(monitoring.intervalFor(FallbackState.FALLBACK)
                        > monitoring.intervalFor(FallbackState.NORMAL),
                "fallback samples less often, because the point is to stop costing anything");
        assertTrue(monitoring.intervalFor(FallbackState.CRITICAL)
                        < monitoring.intervalFor(FallbackState.NORMAL),
                "critical samples more often, to catch a collapse early");
    }
}
