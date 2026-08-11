package com.servertune.selfmonitor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the console does, and does not, say about the optimizer's own cost.
 *
 * <p>Two properties are worth more than the rest. First, a fresh install is silent: the operator
 * who has not asked for this information does not get an identical line every five seconds.
 * Second, silence never costs information - every overrun is still recorded, still counted, and
 * still readable from {@code /serverhealth debug}, which is what makes the silent default
 * defensible rather than a way of hiding a problem.
 *
 * <p>The clock is a parameter throughout, so cooldown behaviour is asserted by arithmetic rather
 * than by sleeping.
 */
class BudgetWarningPolicyTest {

    private static final String SUB = "health-monitor";
    private static final long MINUTE = 60_000L;

    /** Logging on, over-budget on, recovery off, 60s cooldown - i.e. what "on" gives you. */
    private static BudgetWarningPolicy loggingOn() {
        return new BudgetWarningPolicy(true,
                new SelfMonitorLogging(true, true, false, false, MINUTE));
    }

    private static BudgetWarningPolicy withLogging(SelfMonitorLogging logging) {
        return new BudgetWarningPolicy(true, logging);
    }

    // -----------------------------------------------------------------------------------------
    // Test 1 and 2: production defaults are silent
    // -----------------------------------------------------------------------------------------

    @Test
    void consoleLoggingIsDisabledByDefault() {
        // The single most important assertion in this file. A fresh install must not narrate its
        // own budget accounting to an operator who never asked.
        assertFalse(SelfMonitorLogging.DEFAULT_ENABLED,
                "a fresh install must not print SelfMonitor warnings");
        assertFalse(SelfMonitorLogging.defaults().enabled());
        assertFalse(new BudgetWarningPolicy(true, SelfMonitorLogging.defaults()).isLoggingActive());
    }

    @Test
    void nothingIsPrintedWhileLoggingIsDisabled() {
        BudgetWarningPolicy policy = new BudgetWarningPolicy(true, SelfMonitorLogging.defaults());

        for (int cycle = 0; cycle < 20; cycle++) {
            assertEquals(BudgetWarningPolicy.Action.NONE,
                    policy.onOverBudget(SUB, 28.0, 5.0, cycle * 5_000L),
                    "cycle " + cycle + " must stay silent");
        }
    }

    @Test
    void aSilentPolicyStillRecordsEveryOverrun() {
        // Silence must not cost information: this is what /serverhealth debug reads.
        BudgetWarningPolicy policy = new BudgetWarningPolicy(true, SelfMonitorLogging.defaults());

        policy.onOverBudget(SUB, 28.0, 5.0, 0L);
        policy.onOverBudget(SUB, 31.5, 5.0, 5_000L);

        BudgetWarningPolicy.Status status = policy.statusOf(SUB);
        assertNotNull(status, "the overrun has to be visible somewhere");
        assertTrue(status.overBudget());
        assertEquals(31.5, status.lastValueMs());
        assertEquals(5.0, status.budgetMs());
        assertFalse(status.hasWarned(), "recorded, but never printed");
    }

    // -----------------------------------------------------------------------------------------
    // Test 3 and 4: enabling it works
    // -----------------------------------------------------------------------------------------

    @Test
    void loggingCanBeEnabledInConfiguration() {
        BudgetWarningPolicy policy = loggingOn();

        assertTrue(policy.isLoggingActive());
        assertTrue(policy.isOverBudgetLoggingActive());
        assertEquals(BudgetWarningPolicy.LoggingSource.CONFIG, policy.getLoggingSource());
    }

    @Test
    void theFirstOverrunPrintsImmediately() {
        // A new problem must never wait out a cooldown it was not part of.
        assertEquals(BudgetWarningPolicy.Action.WARN,
                loggingOn().onOverBudget(SUB, 28.0, 5.0, 1_000L));
    }

    @Test
    void overBudgetLoggingCanBeDisabledWhileLoggingStaysOn() {
        BudgetWarningPolicy policy = withLogging(
                new SelfMonitorLogging(true, false, true, false, MINUTE));

        assertTrue(policy.isLoggingActive());
        assertFalse(policy.isOverBudgetLoggingActive());
        assertEquals(BudgetWarningPolicy.Action.NONE, policy.onOverBudget(SUB, 28.0, 5.0, 0L));
    }

    // -----------------------------------------------------------------------------------------
    // Test 5: repeats are rate limited
    // -----------------------------------------------------------------------------------------

    @Test
    void repeatedOverrunsInsideTheWindowAreSuppressed() {
        BudgetWarningPolicy policy = loggingOn();

        assertEquals(BudgetWarningPolicy.Action.WARN, policy.onOverBudget(SUB, 28.0, 5.0, 0L));

        // The health monitor samples every 5s by default; a minute of that is 11 more cycles.
        for (long t = 5_000L; t < MINUTE; t += 5_000L) {
            assertEquals(BudgetWarningPolicy.Action.SUPPRESS,
                    policy.onOverBudget(SUB, 28.0, 5.0, t),
                    "t=" + t + "ms is still inside the 60s window");
        }
    }

    @Test
    void theWarningReturnsOnceTheWindowExpires() {
        BudgetWarningPolicy policy = loggingOn();

        policy.onOverBudget(SUB, 28.0, 5.0, 0L);
        policy.onOverBudget(SUB, 28.0, 5.0, 30_000L);

        assertEquals(BudgetWarningPolicy.Action.WARN, policy.onOverBudget(SUB, 28.0, 5.0, MINUTE),
                "exactly at the boundary the window has elapsed");
    }

    @Test
    void suppressedOverrunsAreCountedAndReportedOnTheNextWarning() {
        // Otherwise a rate limit would understate the problem: one line an hour reads like one
        // overrun an hour.
        BudgetWarningPolicy policy = loggingOn();

        policy.onOverBudget(SUB, 28.0, 5.0, 0L);
        policy.onOverBudget(SUB, 28.0, 5.0, 5_000L);
        policy.onOverBudget(SUB, 28.0, 5.0, 10_000L);

        assertEquals(2L, policy.getSuppressedSinceWarn(SUB));

        policy.onOverBudget(SUB, 28.0, 5.0, MINUTE);
        assertEquals(0L, policy.getSuppressedSinceWarn(SUB),
                "the count resets once it has been reported");
        assertEquals(2L, policy.getSuppressedAtLastWarn(SUB),
                "and the warning being composed still gets the number it is meant to quote");
    }

    @Test
    void eachWarningQuotesOnlyItsOwnWindow() {
        // The failure this rules out: a counter that is never restarted, so the second warning
        // reports the first window's overruns again on top of its own and the number climbs
        // forever - which reads as an escalating problem when nothing has changed.
        BudgetWarningPolicy policy = loggingOn();

        policy.onOverBudget(SUB, 28.0, 5.0, 0L);                      // warns
        policy.onOverBudget(SUB, 28.0, 5.0, 20_000L);                 // suppressed
        policy.onOverBudget(SUB, 28.0, 5.0, 40_000L);                 // suppressed

        policy.onOverBudget(SUB, 28.0, 5.0, MINUTE);                  // warns: 2 in that window
        assertEquals(2L, policy.getSuppressedAtLastWarn(SUB));

        policy.onOverBudget(SUB, 28.0, 5.0, MINUTE + 20_000L);        // suppressed
        policy.onOverBudget(SUB, 28.0, 5.0, 2 * MINUTE);              // warns: 1, not 3

        assertEquals(1L, policy.getSuppressedAtLastWarn(SUB),
                "the second window suppressed one overrun, and that is all it reports");
    }

    @Test
    void recoveryClearsTheSuppressionCountSoTheNextStretchStartsFromZero() {
        BudgetWarningPolicy policy = withLogging(
                new SelfMonitorLogging(true, true, true, false, MINUTE));

        policy.onOverBudget(SUB, 28.0, 5.0, 0L);
        policy.onOverBudget(SUB, 28.0, 5.0, 5_000L);
        policy.onOverBudget(SUB, 28.0, 5.0, 10_000L);
        assertEquals(2L, policy.getSuppressedSinceWarn(SUB));

        policy.onWithinBudget(SUB, 2.0, 5.0, 15_000L);
        assertEquals(0L, policy.getSuppressedSinceWarn(SUB));

        // A later, unrelated stretch must not inherit the old count.
        assertEquals(BudgetWarningPolicy.Action.WARN,
                policy.onOverBudget(SUB, 28.0, 5.0, 20_000L));
        assertEquals(0L, policy.getSuppressedAtLastWarn(SUB),
                "nothing was suppressed in this stretch, so the warning has no repeat note");
    }

    @Test
    void totalSuppressedKeepsCountingAcrossWindows() {
        // The per-window count restarts; the lifetime count is what /serverhealth debug shows
        // for a subsystem whose warnings were never printed, so it must not restart with it.
        BudgetWarningPolicy policy = loggingOn();

        policy.onOverBudget(SUB, 28.0, 5.0, 0L);
        policy.onOverBudget(SUB, 28.0, 5.0, 20_000L);
        policy.onOverBudget(SUB, 28.0, 5.0, 40_000L);
        policy.onOverBudget(SUB, 28.0, 5.0, MINUTE);
        policy.onOverBudget(SUB, 28.0, 5.0, MINUTE + 20_000L);

        assertEquals(3L, policy.statusOf(SUB).totalSuppressed(),
                "three overruns went unprinted in total, across two windows");
    }

    // -----------------------------------------------------------------------------------------
    // Test 6: cooldowns are per subsystem
    // -----------------------------------------------------------------------------------------

    @Test
    void eachSubsystemHasItsOwnIndependentCooldown() {
        // The failure this prevents: a permanently noisy health-monitor holding down a global
        // timer so that a module which starts misbehaving is never mentioned at all.
        BudgetWarningPolicy policy = loggingOn();

        assertEquals(BudgetWarningPolicy.Action.WARN,
                policy.onOverBudget("health-monitor", 28.0, 5.0, 0L));
        assertEquals(BudgetWarningPolicy.Action.SUPPRESS,
                policy.onOverBudget("health-monitor", 28.0, 5.0, 5_000L));

        assertEquals(BudgetWarningPolicy.Action.WARN,
                policy.onOverBudget("item-optimization", 9.0, 5.0, 5_000L),
                "a different subsystem's first overrun must print immediately");
    }

    @Test
    void oneSubsystemRecoveringDoesNotClearAnother() {
        BudgetWarningPolicy policy = withLogging(
                new SelfMonitorLogging(true, true, true, false, MINUTE));

        policy.onOverBudget("health-monitor", 28.0, 5.0, 0L);
        policy.onOverBudget("deep-analysis", 40.0, 10.0, 0L);

        policy.onWithinBudget("deep-analysis", 3.0, 10.0, 1_000L);

        assertFalse(policy.statusOf("deep-analysis").overBudget());
        assertTrue(policy.statusOf("health-monitor").overBudget(),
                "health-monitor is still over budget and must still say so");
    }

    // -----------------------------------------------------------------------------------------
    // Test 7 and 8: recovery
    // -----------------------------------------------------------------------------------------

    @Test
    void recoveryIsReportedExactlyOncePerTransition() {
        BudgetWarningPolicy policy = withLogging(
                new SelfMonitorLogging(true, true, true, false, MINUTE));

        policy.onOverBudget(SUB, 28.0, 5.0, 0L);

        assertEquals(BudgetWarningPolicy.Action.RECOVERED,
                policy.onWithinBudget(SUB, 2.0, 5.0, 10_000L));

        for (long t = 15_000L; t < 60_000L; t += 5_000L) {
            assertEquals(BudgetWarningPolicy.Action.NONE, policy.onWithinBudget(SUB, 2.0, 5.0, t),
                    "a healthy subsystem must not keep announcing its health");
        }
    }

    @Test
    void recoveryLoggingIsDisabledByDefault() {
        assertFalse(SelfMonitorLogging.DEFAULT_RECOVERY);
        assertFalse(SelfMonitorLogging.defaults().recovery());

        // Even with logging switched on, recovery stays opt-in.
        assertFalse(loggingOn().isRecoveryLoggingActive());
    }

    @Test
    void recoveryCanBeDisabledIndependentlyOfWarnings() {
        BudgetWarningPolicy policy = loggingOn();   // over-budget on, recovery off

        assertEquals(BudgetWarningPolicy.Action.WARN, policy.onOverBudget(SUB, 28.0, 5.0, 0L));
        assertEquals(BudgetWarningPolicy.Action.NONE,
                policy.onWithinBudget(SUB, 2.0, 5.0, 10_000L),
                "warnings on, recovery off - and the two do not interfere");

        // The transition state was still cleared, so the next overrun warns immediately rather
        // than being treated as a continuation of the old one.
        assertEquals(BudgetWarningPolicy.Action.WARN, policy.onOverBudget(SUB, 28.0, 5.0, 11_000L));
    }

    @Test
    void aSubsystemThatNeverWarnedHasNothingToRecoverFrom() {
        BudgetWarningPolicy policy = withLogging(
                new SelfMonitorLogging(true, true, true, false, MINUTE));

        assertEquals(BudgetWarningPolicy.Action.NONE, policy.onWithinBudget(SUB, 2.0, 5.0, 0L),
                "an unseen subsystem coming in fast is not news");
        assertNull(policy.statusOf(SUB));
    }

    @Test
    void recoveryIsNotAnnouncedForOverrunsThatWereNeverPrinted() {
        // Turning recovery on later must not produce a recovery line for something whose
        // warnings were suppressed the whole time.
        BudgetWarningPolicy policy = withLogging(
                new SelfMonitorLogging(true, false, true, false, MINUTE));

        policy.onOverBudget(SUB, 28.0, 5.0, 0L);
        assertEquals(BudgetWarningPolicy.Action.NONE, policy.onWithinBudget(SUB, 2.0, 5.0, 5_000L));
    }

    // -----------------------------------------------------------------------------------------
    // Test 9, 10, 11: the runtime override
    // -----------------------------------------------------------------------------------------

    @Test
    void theRuntimeToggleTurnsLoggingOnWithoutTouchingConfiguration() {
        BudgetWarningPolicy policy = new BudgetWarningPolicy(true, SelfMonitorLogging.defaults());
        assertFalse(policy.isLoggingActive());

        policy.setRuntimeLogging(true);

        assertTrue(policy.isLoggingActive());
        assertEquals(BudgetWarningPolicy.Action.WARN, policy.onOverBudget(SUB, 28.0, 5.0, 0L));

        // The configured values are what would be written to disk, and they are untouched.
        assertFalse(policy.getConfigured().enabled(),
                "the runtime toggle must not rewrite the configured value");
        assertEquals(BudgetWarningPolicy.LoggingSource.RUNTIME, policy.getLoggingSource());
    }

    @Test
    void theRuntimeToggleCanAlsoSilenceAServerThatConfiguresLoggingOn() {
        BudgetWarningPolicy policy = loggingOn();

        policy.setRuntimeLogging(false);

        assertFalse(policy.isLoggingActive());
        assertEquals(BudgetWarningPolicy.Action.NONE, policy.onOverBudget(SUB, 28.0, 5.0, 0L));
        assertTrue(policy.getConfigured().enabled(), "config.yml still says on");
        assertEquals(BudgetWarningPolicy.LoggingSource.RUNTIME_OFF, policy.getLoggingSource());
    }

    @Test
    void aRuntimeOverrideDoesNotSurviveTheRebuildThatReloadAndRestartPerform() {
        // SelfPerformanceMonitor.reload() and plugin startup both construct a fresh policy from
        // config, which is what makes the override safe to hand out: it cannot outlive the
        // session that set it.
        BudgetWarningPolicy session = new BudgetWarningPolicy(true, SelfMonitorLogging.defaults());
        session.setRuntimeLogging(true);
        session.setRuntimeVerbose(true);
        assertTrue(session.isLoggingActive());

        BudgetWarningPolicy afterRestart =
                new BudgetWarningPolicy(true, SelfMonitorLogging.defaults());

        assertFalse(afterRestart.isLoggingActive(), "a restart must come back silent");
        assertFalse(afterRestart.hasRuntimeOverride());
        assertEquals(BudgetWarningPolicy.LoggingSource.OFF, afterRestart.getLoggingSource());
    }

    @Test
    void clearingTheOverrideHandsTheDecisionBackToConfiguration() {
        BudgetWarningPolicy policy = loggingOn();

        policy.setRuntimeLogging(false);
        assertTrue(policy.hasRuntimeOverride());

        policy.clearRuntimeOverrides();

        assertFalse(policy.hasRuntimeOverride());
        assertTrue(policy.isLoggingActive());
        assertEquals(BudgetWarningPolicy.LoggingSource.CONFIG, policy.getLoggingSource());
    }

    @Test
    void theRuntimeToggleLeavesRecordedMeasurementsAlone() {
        BudgetWarningPolicy policy = new BudgetWarningPolicy(true, SelfMonitorLogging.defaults());
        policy.onOverBudget(SUB, 28.0, 5.0, 0L);

        policy.setRuntimeLogging(true);

        BudgetWarningPolicy.Status status = policy.statusOf(SUB);
        assertNotNull(status, "toggling logging must not discard what was already measured");
        assertEquals(28.0, status.lastValueMs());
    }

    // -----------------------------------------------------------------------------------------
    // Verbose
    // -----------------------------------------------------------------------------------------

    @Test
    void verboseIsOffByDefaultAndMeansNothingWhileLoggingIsOff() {
        assertFalse(SelfMonitorLogging.DEFAULT_VERBOSE);

        BudgetWarningPolicy policy = new BudgetWarningPolicy(true,
                new SelfMonitorLogging(false, true, false, true, MINUTE));

        assertFalse(policy.isVerbose(),
                "verbose adds detail to lines that print; with none printing it is inert");
    }

    @Test
    void verboseDoesNotAffectTheCooldown() {
        // The property that keeps "verbose" from ever meaning "log every tick": it changes what
        // a line contains, never how often lines happen.
        BudgetWarningPolicy verbose = withLogging(
                new SelfMonitorLogging(true, true, false, true, MINUTE));

        assertEquals(BudgetWarningPolicy.Action.WARN, verbose.onOverBudget(SUB, 28.0, 5.0, 0L));
        for (long t = 5_000L; t < MINUTE; t += 5_000L) {
            assertEquals(BudgetWarningPolicy.Action.SUPPRESS,
                    verbose.onOverBudget(SUB, 28.0, 5.0, t),
                    "verbose must not shorten or bypass the window at t=" + t);
        }
        assertTrue(verbose.isVerbose());
    }

    @Test
    void verboseCanBeToggledAtRuntimeWithoutEnablingLogging() {
        BudgetWarningPolicy policy = new BudgetWarningPolicy(true, SelfMonitorLogging.defaults());

        policy.setRuntimeVerbose(true);

        assertFalse(policy.isLoggingActive(), "verbose is not a way to switch logging on");
        assertFalse(policy.isVerbose());
    }

    // -----------------------------------------------------------------------------------------
    // Self-monitoring disabled entirely
    // -----------------------------------------------------------------------------------------

    @Test
    void disablingSelfMonitoringOutrightSilencesEverythingIncludingTheOverride() {
        // Nothing is being measured, so there is nothing to say - and the runtime override cannot
        // manufacture data that was never collected. self-monitoring.enabled wins over every
        // logging setting, config or runtime.
        BudgetWarningPolicy policy = new BudgetWarningPolicy(false,
                new SelfMonitorLogging(true, true, true, true, MINUTE));

        assertFalse(policy.isLoggingActive());
        assertEquals(BudgetWarningPolicy.LoggingSource.OFF, policy.getLoggingSource());

        policy.setRuntimeLogging(true);
        assertFalse(policy.isLoggingActive(),
                "there are no measurements to report, so the override has nothing to enable");
        assertEquals(BudgetWarningPolicy.Action.NONE, policy.onOverBudget(SUB, 28.0, 5.0, 0L));
    }

    @Test
    void resetClearsRecordedStateButNotSettings() {
        BudgetWarningPolicy policy = loggingOn();
        policy.onOverBudget(SUB, 28.0, 5.0, 0L);

        policy.reset();

        assertNull(policy.statusOf(SUB));
        assertTrue(policy.isLoggingActive(), "reset clears measurements, not configuration");
        assertEquals(BudgetWarningPolicy.Action.WARN, policy.onOverBudget(SUB, 28.0, 5.0, 1_000L),
                "and the first warning after a reset is immediate again");
    }
}
