package com.servertune.guard;

import com.servertune.fallback.FallbackState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec section 9: drive the exact required sequence and prove the transitions are stable.
 *
 * <p>20, 19, 18, 17, 15, 10, 9, 5, 10, 15, 18, 20 - a gradual decline into a crash and a
 * gradual climb back out.
 */
class TpsSequenceStabilityTest {

    private static final double[] SEQUENCE =
            {20, 19, 18, 17, 15, 10, 9, 5, 10, 15, 18, 20};

    /** Seconds each step is held, comfortably longer than the longest sustained window. */
    private static final int SECONDS_PER_STEP = 15;

    /**
     * How long the simulated staged recovery takes. In the running plugin
     * {@link PerformanceGuard} schedules six Bukkit tasks and calls
     * {@code completeRecovery()} from the last one; here the same hand-off is modelled
     * directly, because the state machine deliberately does not leave RECOVERING on its own.
     */
    private static final int RECOVERY_STAGE_SECONDS = 30;

    private record Step(double tps, FallbackState state, int transitions) {
    }

    private long clock = 1_000_000L;
    private long recoveringSince = -1L;

    /** One second of simulated time, including the guard's staged-recovery hand-off. */
    private void tick(PerformanceStateMachine machine, double tps, double mspt) {
        clock += 1000L;
        machine.evaluate(tps, mspt, clock);

        // Stand in for the guard's staged recovery finishing. On a live server stage 6
        // fires 30s after entering RECOVERING and calls completeRecovery() itself.
        if (machine.getCurrentState() == FallbackState.RECOVERING) {
            if (recoveringSince < 0) {
                recoveringSince = clock;
            } else if (clock - recoveringSince >= RECOVERY_STAGE_SECONDS * 1000L) {
                machine.completeRecovery(clock);
                recoveringSince = -1L;
            }
        } else {
            recoveringSince = -1L;
        }
    }

    private List<Step> run(PerformanceStateMachine machine, int secondsPerStep) {
        List<Step> log = new ArrayList<>();

        for (double tps : SEQUENCE) {
            double mspt = tps >= 20.0 ? 20.0 : 1000.0 / tps;
            int before = machine.getTransitionCount();

            for (int i = 0; i < secondsPerStep; i++) {
                tick(machine, tps, mspt);
            }

            log.add(new Step(tps, machine.getCurrentState(),
                    machine.getTransitionCount() - before));
        }

        return log;
    }

    /**
     * Keeps the healthy metric flowing after the sequence ends, so the staged recovery has
     * room to finish. The sequence's last step is shorter than a full recovery staging, and
     * a real server does not stop ticking the moment TPS is back at 20.
     */
    private void settle(PerformanceStateMachine machine, int seconds) {
        for (int i = 0; i < seconds; i++) {
            tick(machine, 20.0, 20.0);
        }
    }

    @Test
    void theRequiredSequenceEndsHealthyAndNeverThrashes() {
        PerformanceStateMachine machine =
                new PerformanceStateMachine(GuardThresholds.defaults(), 1_000_000L);

        List<Step> log = run(machine, SECONDS_PER_STEP);

        System.out.println("TPS sequence walk:");
        for (Step step : log) {
            System.out.printf("  %5.1f TPS -> %-10s (%d transition%s)%n",
                    step.tps(), step.state(), step.transitions(),
                    step.transitions() == 1 ? "" : "s");
        }

        // No single steady metric may cause more than one state change. More than one means
        // the machine moved and then moved again on unchanging input: that is thrashing.
        for (Step step : log) {
            assertTrue(step.transitions() <= 1, String.format(
                    "at a steady %.1f TPS the state changed %d times - that is thrashing",
                    step.tps(), step.transitions()));
        }

        // The decline must reach FALLBACK, and the climb must end back at NORMAL.
        assertSame(FallbackState.NORMAL, log.get(0).state(), "20 TPS is healthy");
        assertSame(FallbackState.FALLBACK, log.get(7).state(), "5 TPS must be fallback");

        // The last step gets the machine into RECOVERING; leaving RECOVERING belongs to the
        // guard's staged recovery, so let the healthy metric keep flowing and check it lands.
        assertSame(FallbackState.RECOVERING, log.get(log.size() - 1).state(),
                "a sustained climb back to 20 TPS must start recovery");

        settle(machine, 60);
        assertSame(FallbackState.NORMAL, machine.getCurrentState(),
                "the sequence must end recovered, not stuck");
    }

    @Test
    void theWalkIsMonotonicThroughTheDecline() {
        PerformanceStateMachine machine =
                new PerformanceStateMachine(GuardThresholds.defaults(), 1_000_000L);

        List<Step> log = run(machine, SECONDS_PER_STEP);

        // Steps 0..7 are the decline: severity must never improve while TPS is falling.
        int previous = PerformanceStateMachine.rank(log.get(0).state());
        for (int i = 1; i <= 7; i++) {
            int current = PerformanceStateMachine.rank(log.get(i).state());
            assertTrue(current >= previous, String.format(
                    "severity went backwards at %.1f TPS (%s after %s)",
                    log.get(i).tps(), log.get(i).state(), log.get(i - 1).state()));
            previous = current;
        }
    }

    @Test
    void theWholeSequenceCostsFewTransitions() {
        PerformanceStateMachine machine =
                new PerformanceStateMachine(GuardThresholds.defaults(), 1_000_000L);

        run(machine, SECONDS_PER_STEP);
        settle(machine, 60);

        int total = machine.getTransitionCount();
        System.out.println("Total transitions across the 12-step sequence: " + total);

        // NORMAL -> WARNING -> CRITICAL -> FALLBACK -> RECOVERING -> NORMAL is the shape.
        // Anything much above that means the machine is reacting to noise.
        assertTrue(total <= 6, "expected at most 6 transitions, saw " + total);
        assertTrue(total >= 5, "expected the full decline and recovery, saw only " + total);
    }

    @Test
    void aFastWalkStillDoesNotOverreact() {
        // Each step held only 2 seconds: shorter than every sustained window except
        // fallback's 3s. Only the genuine crash should register.
        PerformanceStateMachine machine =
                new PerformanceStateMachine(GuardThresholds.defaults(), 1_000_000L);

        List<Step> log = run(machine, 2);

        for (Step step : log) {
            assertTrue(step.transitions() <= 1, String.format(
                    "fast walk thrashed at %.1f TPS (%d transitions)",
                    step.tps(), step.transitions()));
        }

        assertTrue(machine.getTransitionCount() <= 3,
                "a brief dip should not walk the whole state ladder, saw "
                        + machine.getTransitionCount() + " transitions");
    }

    @Test
    void aFlappingMetricProducesFarFewerTransitionsThanSamples() {
        PerformanceStateMachine machine =
                new PerformanceStateMachine(GuardThresholds.defaults(), 1_000_000L);

        long clock = 1_000_000L;
        // Alternate either side of the warning threshold every second for two minutes.
        for (int i = 0; i < 120; i++) {
            clock += 1000L;
            machine.evaluate(i % 2 == 0 ? 17.5 : 18.5, 30.0, clock);
        }

        int transitions = machine.getTransitionCount();
        System.out.println("Flapping metric over 120 samples produced " + transitions
                + " transition(s)");

        assertTrue(transitions <= 1, "a metric oscillating across one threshold produced "
                + transitions + " transitions; hysteresis is not holding");
    }

    @Test
    void thresholdsAreFullyConfigurable() {
        // A server tuned to react much earlier: warning at 19.5, critical at 19, fallback
        // at 18, all with short windows.
        GuardThresholds strict = GuardThresholds.builder()
                .warningTps(19.5)
                .criticalTps(19.0)
                .fallbackTps(18.0)
                .recoveryTps(19.8)
                .warningMspt(25.0)
                .criticalMspt(30.0)
                .warningSustainedSeconds(2)
                .criticalSustainedSeconds(2)
                .fallbackSustainedSeconds(2)
                .recoverySustainedSeconds(2)
                .deEscalationSustainedSeconds(2)
                .minStateSeconds(1)
                .build();

        PerformanceStateMachine machine = new PerformanceStateMachine(strict, 1_000_000L);

        long clock = 1_000_000L;
        for (int i = 0; i < 6; i++) {
            clock += 1000L;
            machine.evaluate(17.0, 22.0, clock);
        }

        assertSame(FallbackState.FALLBACK, machine.getCurrentState(),
                "17 TPS is below this config's 18.0 fallback threshold");
        assertEquals(18.0, machine.getThresholds().getFallbackTps());
    }
}
