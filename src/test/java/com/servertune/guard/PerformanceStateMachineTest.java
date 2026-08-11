package com.servertune.guard;

import com.servertune.fallback.FallbackState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the state machine with a controlled clock. No Bukkit, no real time, so every
 * assertion below is deterministic.
 */
class PerformanceStateMachineTest {

    /** A healthy MSPT, so TPS-only tests are not accidentally escalated by MSPT. */
    private static final double GOOD_MSPT = 20.0;

    private long clock = 1_000_000L;

    private PerformanceStateMachine machine() {
        return machine(GuardThresholds.defaults());
    }

    private PerformanceStateMachine machine(GuardThresholds thresholds) {
        return new PerformanceStateMachine(thresholds, clock);
    }

    /** Feeds one metric for {@code seconds}, sampling every second. */
    private List<PerformanceStateMachine.StateTransition> hold(
            PerformanceStateMachine machine, double tps, double mspt, int seconds) {
        List<PerformanceStateMachine.StateTransition> seen = new ArrayList<>();
        for (int i = 0; i < seconds; i++) {
            clock += 1000L;
            PerformanceStateMachine.StateTransition t = machine.evaluate(tps, mspt, clock);
            if (t != null) {
                seen.add(t);
            }
        }
        return seen;
    }

    @Test
    void oneBadSampleChangesNothing() {
        PerformanceStateMachine machine = machine();

        clock += 1000L;
        assertNull(machine.evaluate(5.0, 200.0, clock),
                "a single sample must never move the state");
        assertSame(FallbackState.NORMAL, machine.getCurrentState());
    }

    @Test
    void warningRequiresItsSustainedWindow() {
        PerformanceStateMachine machine = machine();

        // 9 seconds is one short of the 10s warning window
        assertTrue(hold(machine, 17.0, GOOD_MSPT, 9).isEmpty());
        assertSame(FallbackState.NORMAL, machine.getCurrentState());

        List<PerformanceStateMachine.StateTransition> t = hold(machine, 17.0, GOOD_MSPT, 2);
        assertEquals(1, t.size());
        assertSame(FallbackState.WARNING, machine.getCurrentState());
    }

    @Test
    void highMsptEscalatesEvenWhileTpsLooksFine() {
        PerformanceStateMachine machine = machine();

        // TPS a perfect 20, MSPT past the warning threshold. TPS is a lagging average, so a
        // server that has just started to struggle shows this shape first.
        hold(machine, 20.0, 58.0, 12);
        assertSame(FallbackState.WARNING, machine.getCurrentState(),
                "MSPT above the warning threshold must escalate on its own");

        // Push MSPT past critical and it escalates again, still with a perfect TPS.
        hold(machine, 20.0, 80.0, 7);
        assertSame(FallbackState.CRITICAL, machine.getCurrentState());
    }

    @Test
    void anMsptAtExactlyOneTickIsNotTreatedAsDegraded() {
        PerformanceStateMachine machine = machine();

        // MSPT 50 is precisely 20 TPS. If the thresholds were taken from alerts.mspt.* this
        // would sit in CRITICAL forever.
        assertTrue(hold(machine, 20.0, 50.0, 60).isEmpty());
        assertSame(FallbackState.NORMAL, machine.getCurrentState());
    }

    @Test
    void fallbackIgnoresTheMinimumDwellTimer() {
        PerformanceStateMachine machine = machine();

        // Reach WARNING, then crash immediately. min-state-seconds is 5, but the fallback
        // path must not wait for it.
        hold(machine, 17.0, GOOD_MSPT, 11);
        assertSame(FallbackState.WARNING, machine.getCurrentState());

        List<PerformanceStateMachine.StateTransition> t = hold(machine, 5.0, 100.0, 4);

        assertSame(FallbackState.FALLBACK, machine.getCurrentState());
        assertTrue(t.stream().anyMatch(x -> x.to() == FallbackState.FALLBACK));
    }

    @Test
    void hysteresisStopsFlappingOnTheThreshold() {
        PerformanceStateMachine machine = machine();

        hold(machine, 17.0, GOOD_MSPT, 11);
        assertSame(FallbackState.WARNING, machine.getCurrentState());

        // Exactly on the warning threshold. Without hysteresis this would read as NORMAL
        // and flip back and forth every sample.
        assertTrue(hold(machine, 18.0, GOOD_MSPT, 30).isEmpty(),
                "sitting on the threshold must not de-escalate");
        assertSame(FallbackState.WARNING, machine.getCurrentState());

        // Clear it by more than the 1.0 TPS margin and it recovers.
        hold(machine, 19.5, GOOD_MSPT, 11);
        assertSame(FallbackState.NORMAL, machine.getCurrentState());
    }

    @Test
    void recoveryFromFallbackNeedsSustainedGoodTps() {
        PerformanceStateMachine machine = machine();

        hold(machine, 5.0, 100.0, 4);
        assertSame(FallbackState.FALLBACK, machine.getCurrentState());

        // Good TPS but not for long enough
        assertTrue(hold(machine, 19.0, GOOD_MSPT, 9).isEmpty());
        assertSame(FallbackState.FALLBACK, machine.getCurrentState());

        hold(machine, 19.0, GOOD_MSPT, 2);
        assertSame(FallbackState.RECOVERING, machine.getCurrentState());
    }

    @Test
    void recoveryIsAbortedByARelapse() {
        PerformanceStateMachine machine = machine();

        hold(machine, 5.0, 100.0, 4);
        hold(machine, 19.0, GOOD_MSPT, 11);
        assertSame(FallbackState.RECOVERING, machine.getCurrentState());

        hold(machine, 6.0, 120.0, 4);
        assertSame(FallbackState.FALLBACK, machine.getCurrentState(),
                "a relapse during recovery must go straight back to fallback");
    }

    @Test
    void recoveringOnlyLeavesWhenTheOwnerSaysStagesAreDone() {
        PerformanceStateMachine machine = machine();

        hold(machine, 5.0, 100.0, 4);
        hold(machine, 19.0, GOOD_MSPT, 11);
        assertSame(FallbackState.RECOVERING, machine.getCurrentState());

        // Perfect metrics for a long time still do not exit RECOVERING by themselves; the
        // staged recovery owns that transition.
        assertTrue(hold(machine, 20.0, 10.0, 60).isEmpty());
        assertSame(FallbackState.RECOVERING, machine.getCurrentState());

        clock += 1000L;
        PerformanceStateMachine.StateTransition done = machine.completeRecovery(clock);
        assertNotNull(done);
        assertSame(FallbackState.NORMAL, machine.getCurrentState());
    }

    @Test
    void completeRecoveryIsIgnoredOutsideRecovering() {
        PerformanceStateMachine machine = machine();
        assertNull(machine.completeRecovery(clock));
        assertSame(FallbackState.NORMAL, machine.getCurrentState());
    }
}
