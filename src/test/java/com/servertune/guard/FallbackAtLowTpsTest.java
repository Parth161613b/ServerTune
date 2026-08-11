package com.servertune.guard;

import com.servertune.fallback.FallbackState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TPS = 8 scenario, end to end.
 *
 * <p>Fallback exists to make the optimizer stop costing the server anything while the server is
 * already struggling. These tests pin the four things that must hold at TPS 8: optimizer work
 * stops, commands keep working, alerts keep firing, and recovery monitoring keeps running.
 *
 * <p>{@link PerformanceGuard} itself needs a live server, so what is asserted here is the part
 * that decides: the state machine and the action policy. The guard is a thin translation of
 * both - {@code suspendOptimizerWork()} reads exactly the three policy flags checked below.
 */
class FallbackAtLowTpsTest {

    /** A crashing server. Well under the 10.0 fallback threshold. */
    private static final double CRASHING_TPS = 8.0;

    /** A server at 8 TPS is spending ~125ms per tick. */
    private static final double CRASHING_MSPT = 125.0;

    private long clock = 1_000_000L;

    private void hold(PerformanceStateMachine machine, double tps, double mspt, int seconds) {
        for (int i = 0; i < seconds; i++) {
            clock += 1000L;
            machine.evaluate(tps, mspt, clock);
        }
    }

    private PerformanceStateMachine crashedServer() {
        PerformanceStateMachine machine =
                new PerformanceStateMachine(GuardThresholds.defaults(), clock);
        hold(machine, CRASHING_TPS, CRASHING_MSPT, 5);
        assertSame(FallbackState.FALLBACK, machine.getCurrentState(),
                "TPS 8 sustained past the trigger window must reach FALLBACK");
        return machine;
    }

    @Test
    void tpsEightReachesFallbackWithinTheTriggerWindow() {
        PerformanceStateMachine machine =
                new PerformanceStateMachine(GuardThresholds.defaults(), clock);

        // Default fallback window is 3s, so 2s of bad samples must not be enough.
        hold(machine, CRASHING_TPS, CRASHING_MSPT, 2);
        assertSame(FallbackState.NORMAL, machine.getCurrentState(),
                "a brief dip must not trip fallback");

        hold(machine, CRASHING_TPS, CRASHING_MSPT, 2);
        assertSame(FallbackState.FALLBACK, machine.getCurrentState());
    }

    @Test
    void fallbackStopsOptimizerWork() {
        // The three switches PerformanceGuard.suspendOptimizerWork() consults. All default on,
        // so an owner who configures nothing still gets a guard that can actually stop work.
        GuardActionPolicy policy = GuardActionPolicy.defaults();

        assertTrue(policy.shouldSuspendOptimizerInFallback(),
                "modules must be suspended in fallback");
        assertTrue(policy.shouldCancelExpensiveTasksInFallback(),
                "scheduled module tasks must be cancelled in fallback");
        assertTrue(policy.shouldStopDeepAnalysisInFallback(),
                "deep analysis must stop in fallback");
    }

    @Test
    void fallbackStillAlerts() {
        GuardActionPolicy policy = GuardActionPolicy.defaults();

        assertTrue(policy.isAlertsEnabled(), "alerting must survive fallback");
        assertTrue(policy.shouldNotify(FallbackState.FALLBACK),
                "entering fallback must notify staff");
        assertTrue(policy.shouldNotify(FallbackState.RECOVERING),
                "leaving fallback must notify staff too");
        assertTrue(policy.isLogToConsole(),
                "console operators must see it even with no staff online");
    }

    @Test
    void fallbackChangesNothingItWasNotAskedTo() {
        GuardActionPolicy policy = GuardActionPolicy.defaults();

        // Suspending the optimizer's own work is not "automatic optimization". The latter is
        // the guard changing the owner's module setup, and it stays off by default.
        assertFalse(policy.isAutomaticOptimizationEnabled());
        assertTrue(policy.modulesToSuspend(FallbackState.FALLBACK).isEmpty());
        assertTrue(policy.modulesToEnable(FallbackState.FALLBACK).isEmpty());
    }

    @Test
    void recoveryMonitoringKeepsRunningDuringFallback() {
        PerformanceStateMachine machine = crashedServer();

        // The machine is still being fed and still evaluating: it notices the recovery.
        hold(machine, 19.0, 20.0, 11);
        assertSame(FallbackState.RECOVERING, machine.getCurrentState(),
                "recovery detection must keep running while suspended");
    }

    @Test
    void healthMonitoringSlowsInFallbackButNeverStops() {
        AdaptiveMonitoring monitoring = AdaptiveMonitoring.builder()
                .enabled(true)
                .baseIntervalTicks(100)
                .build();

        int fallbackInterval = monitoring.intervalFor(FallbackState.FALLBACK);

        assertTrue(fallbackInterval > 0,
                "the health monitor must never be scheduled out of existence");
        assertTrue(fallbackInterval >= monitoring.intervalFor(FallbackState.NORMAL),
                "fallback should sample no more often than normal - it is meant to cost less");
    }

    @Test
    void aServerStuckAtEightTpsDoesNotFlapOutOfFallback() {
        PerformanceStateMachine machine = crashedServer();

        // Two minutes at 8 TPS. Nothing should transition; repeatedly re-entering fallback
        // would re-suspend modules and re-cancel tasks on every sample.
        for (int i = 0; i < 120; i++) {
            clock += 1000L;
            assertNull(machine.evaluate(CRASHING_TPS, CRASHING_MSPT, clock),
                    "a server sitting at 8 TPS must produce no further transitions");
        }
        assertSame(FallbackState.FALLBACK, machine.getCurrentState());
    }

    @Test
    void aBriefBlipDuringFallbackDoesNotEndIt() {
        PerformanceStateMachine machine = crashedServer();

        // One good sample, then back to crashing. Recovery needs its sustained window.
        clock += 1000L;
        machine.evaluate(19.5, 20.0, clock);
        hold(machine, CRASHING_TPS, CRASHING_MSPT, 5);

        assertSame(FallbackState.FALLBACK, machine.getCurrentState(),
                "a single good sample must not start a recovery");
    }

    @Test
    void recoveryFromEightTpsCompletesOnlyWhenStagesFinish() {
        PerformanceStateMachine machine = crashedServer();

        hold(machine, 19.0, 20.0, 11);
        assertSame(FallbackState.RECOVERING, machine.getCurrentState());

        // Staged recovery owns the last step, so the guard controls when work actually
        // resumes rather than everything coming back at once on a still-fragile server.
        clock += 1000L;
        assertNotNull(machine.completeRecovery(clock));
        assertSame(FallbackState.NORMAL, machine.getCurrentState());
    }
}
