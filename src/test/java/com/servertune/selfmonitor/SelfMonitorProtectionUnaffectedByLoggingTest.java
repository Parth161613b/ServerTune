package com.servertune.selfmonitor;

import com.servertune.guard.BudgetViolationTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line between "how loud is the console" and "what does the plugin do".
 *
 * <p>Silencing the console is only defensible if it silences the console and nothing else. These
 * tests pin that: the same sequence of executions produces the same decisions - warn, suspend,
 * protect - whether logging is on, off, or overridden at runtime, and the recorded state that
 * {@code /serverhealth debug} reads is identical either way.
 *
 * <p>{@link BudgetViolationTracker} is the component that decides, and it holds no reference to
 * {@link BudgetWarningPolicy} at all; the pairing exists only in {@link SelfPerformanceMonitor},
 * which asks the tracker what to do and then asks the policy whether to mention it. These tests
 * exercise both halves in that order without the plugin.
 */
class SelfMonitorProtectionUnaffectedByLoggingTest {

    private static final long MINUTE = 60_000L;

    private static BudgetViolationTracker tracker() {
        return BudgetViolationTracker.builder()
                .enabled(true)
                .warnOnExceeded(true)
                .suspendOnExceeded(true)
                .violationsBeforeSuspend(3)
                .defaultBudgetMs(5.0)
                .budget("health-monitor", 5.0)
                .budget("metrics-collection", 5.0)
                .build();
    }

    private static BudgetWarningPolicy silent() {
        return new BudgetWarningPolicy(true, SelfMonitorLogging.defaults());
    }

    private static BudgetWarningPolicy loud() {
        return new BudgetWarningPolicy(true,
                new SelfMonitorLogging(true, true, true, true, MINUTE));
    }

    @Test
    void aModuleIsStillSuspendedOnASilentServer() {
        // The failure this rules out: shipping a quiet default that also quietly stops protecting
        // the server.
        BudgetViolationTracker tracker = tracker();
        BudgetWarningPolicy policy = silent();

        assertEquals(BudgetViolationTracker.Decision.WARN,
                tracker.record("item-optimization", 40.0));
        policy.onOverBudget("item-optimization", 40.0, 5.0, 0L);

        assertEquals(BudgetViolationTracker.Decision.WARN,
                tracker.record("item-optimization", 41.0));
        policy.onOverBudget("item-optimization", 41.0, 5.0, 5_000L);

        assertEquals(BudgetViolationTracker.Decision.SUSPEND,
                tracker.record("item-optimization", 42.0),
                "the third consecutive overrun suspends the module, silent console or not");
    }

    @Test
    void theDecisionSequenceIsIdenticalWithLoggingOnAndOff() {
        BudgetViolationTracker withLogging = tracker();
        BudgetViolationTracker withoutLogging = tracker();
        BudgetWarningPolicy loud = loud();
        BudgetWarningPolicy silent = silent();

        double[] executions = {40.0, 2.0, 40.0, 41.0, 42.0, 43.0, 1.0, 44.0};

        for (int i = 0; i < executions.length; i++) {
            long now = i * 5_000L;
            double ms = executions[i];

            BudgetViolationTracker.Decision a = withLogging.record("item-optimization", ms);
            BudgetViolationTracker.Decision b = withoutLogging.record("item-optimization", ms);

            // Drive the policies exactly as SelfPerformanceMonitor does, so any coupling between
            // the two would show up here.
            if (a == BudgetViolationTracker.Decision.OK) {
                loud.onWithinBudget("item-optimization", ms, 5.0, now);
                silent.onWithinBudget("item-optimization", ms, 5.0, now);
            } else {
                loud.onOverBudget("item-optimization", ms, 5.0, now);
                silent.onOverBudget("item-optimization", ms, 5.0, now);
            }

            assertEquals(a, b, "execution " + i + " (" + ms + " ms) must decide the same either way");
        }
    }

    @Test
    void protectedSubsystemsAreStillProtectedRegardlessOfLogging() {
        // health-monitor and metrics-collection can never be suspended: suspending them would
        // blind the guard, which is what detects TPS collapse and drives fallback. A logging
        // setting must not be able to change that in either direction.
        for (String subsystem : BudgetViolationTracker.PROTECTED_SUBSYSTEMS) {
            BudgetViolationTracker tracker = tracker();

            tracker.record(subsystem, 40.0);
            tracker.record(subsystem, 40.0);

            assertEquals(BudgetViolationTracker.Decision.WARN_PROTECTED,
                    tracker.record(subsystem, 40.0),
                    subsystem + " must warn, never suspend");
        }
    }

    @Test
    void theStateBehindServerhealthDebugIsTheSameWhetherOrNotAnythingWasPrinted() {
        // /serverhealth debug is the reason console logging can default to off. If a silent
        // server recorded less, the default would be hiding the problem rather than not
        // repeating it.
        BudgetWarningPolicy loud = loud();
        BudgetWarningPolicy silent = silent();

        for (int i = 0; i < 5; i++) {
            loud.onOverBudget("health-monitor", 28.0, 5.0, i * 5_000L);
            silent.onOverBudget("health-monitor", 28.0, 5.0, i * 5_000L);
        }

        BudgetWarningPolicy.Status loudStatus = loud.statusOf("health-monitor");
        BudgetWarningPolicy.Status silentStatus = silent.statusOf("health-monitor");

        assertNotNull(silentStatus, "a silent server still has to be able to report the overrun");
        assertEquals(loudStatus.overBudget(), silentStatus.overBudget());
        assertEquals(loudStatus.lastValueMs(), silentStatus.lastValueMs());
        assertEquals(loudStatus.budgetMs(), silentStatus.budgetMs());

        // The one legitimate difference: the loud policy printed once and suppressed four, the
        // silent one printed nothing. Both know five overruns happened.
        assertTrue(loudStatus.hasWarned());
        assertFalse(silentStatus.hasWarned());
    }

    @Test
    void aRuntimeOverrideChangesNoDecision() {
        BudgetViolationTracker tracker = tracker();
        BudgetWarningPolicy policy = silent();

        tracker.record("item-optimization", 40.0);
        policy.onOverBudget("item-optimization", 40.0, 5.0, 0L);

        policy.setRuntimeLogging(true);

        tracker.record("item-optimization", 41.0);
        policy.onOverBudget("item-optimization", 41.0, 5.0, 5_000L);

        assertEquals(BudgetViolationTracker.Decision.SUSPEND,
                tracker.record("item-optimization", 42.0),
                "turning logging on mid-streak must not reset or extend the streak");
    }

    @Test
    void oneGoodExecutionStillClearsTheStreakOnASilentServer() {
        BudgetViolationTracker tracker = tracker();
        BudgetWarningPolicy policy = silent();

        tracker.record("item-optimization", 40.0);
        tracker.record("item-optimization", 40.0);
        policy.onOverBudget("item-optimization", 40.0, 5.0, 0L);

        assertEquals(BudgetViolationTracker.Decision.OK, tracker.record("item-optimization", 1.0));
        policy.onWithinBudget("item-optimization", 1.0, 5.0, 5_000L);

        assertEquals(BudgetViolationTracker.Decision.WARN,
                tracker.record("item-optimization", 40.0),
                "back to the start of the streak, not one execution from suspension");
    }
}
