package com.servertune.selfmonitor;

import com.servertune.config.ConfigRules;
import com.servertune.config.ConfigValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that stops a configured cooldown from becoming no cooldown.
 *
 * <p>Every other duration in config.yml is honoured as written, and several of them accept zero as
 * a meaningful "no margin". This one does not, and the difference is deliberate: the health monitor
 * samples every five seconds by default, so a subsystem that sits over budget with a one-second
 * cooldown produces a line every five seconds indefinitely. There is no setting for that, because
 * there is no server on which it is the right answer - {@code /serverhealth debug} already gives
 * the same information on demand and for free.
 *
 * <p>Both readers of the value - {@link ConfigRules#logCooldownSeconds} for the validator and
 * {@link SelfMonitorLogging#of} for the policy - call one function, so they cannot disagree about
 * what a given number means.
 */
class SelfMonitorLoggingTest {

    private static final String PATH = "self-monitoring.logging.cooldown-seconds";

    // -----------------------------------------------------------------------------------------
    // The minimum
    // -----------------------------------------------------------------------------------------

    @Test
    void zeroMeansUnsetRatherThanLogEveryCycle() {
        assertEquals(SelfMonitorLogging.DEFAULT_COOLDOWN_SECONDS,
                ConfigRules.resolveLogCooldownSeconds(0, SelfMonitorLogging.DEFAULT_COOLDOWN_SECONDS),
                "0 must not be read as 'no cooldown'");
    }

    @Test
    void aNegativeCooldownIsAlsoTreatedAsUnset() {
        assertEquals(SelfMonitorLogging.DEFAULT_COOLDOWN_SECONDS,
                ConfigRules.resolveLogCooldownSeconds(-30,
                        SelfMonitorLogging.DEFAULT_COOLDOWN_SECONDS));
    }

    @Test
    void aCooldownBelowTheFloorIsRaisedToIt() {
        // 1s against a 5s sample interval is not meaningfully different from none at all.
        for (int seconds = 1; seconds < ConfigRules.MIN_LOG_COOLDOWN_SECONDS; seconds++) {
            assertEquals(ConfigRules.MIN_LOG_COOLDOWN_SECONDS,
                    ConfigRules.resolveLogCooldownSeconds(seconds, 60),
                    seconds + "s is below the floor and must be raised");
        }
    }

    @Test
    void theFloorItselfIsAccepted() {
        assertEquals(ConfigRules.MIN_LOG_COOLDOWN_SECONDS,
                ConfigRules.resolveLogCooldownSeconds(ConfigRules.MIN_LOG_COOLDOWN_SECONDS, 60));
    }

    @Test
    void anyValueAboveTheFloorIsUsedAsWritten() {
        assertEquals(60, ConfigRules.resolveLogCooldownSeconds(60, 60));
        assertEquals(300, ConfigRules.resolveLogCooldownSeconds(300, 60));
        assertEquals(86_400, ConfigRules.resolveLogCooldownSeconds(86_400, 60),
                "a very long cooldown is a legitimate choice and is not second-guessed");
    }

    @Test
    void theShippedDefaultIsItselfAboveTheFloor() {
        // A default that failed its own rule would be corrected on every single startup.
        assertTrue(SelfMonitorLogging.DEFAULT_COOLDOWN_SECONDS
                        >= ConfigRules.MIN_LOG_COOLDOWN_SECONDS,
                "the default has to satisfy the rule it is the fallback for");
        assertEquals(SelfMonitorLogging.DEFAULT_COOLDOWN_SECONDS,
                ConfigRules.resolveLogCooldownSeconds(SelfMonitorLogging.DEFAULT_COOLDOWN_SECONDS,
                        SelfMonitorLogging.DEFAULT_COOLDOWN_SECONDS));
    }

    // -----------------------------------------------------------------------------------------
    // What the operator is told about it
    // -----------------------------------------------------------------------------------------

    @Test
    void aCorrectedCooldownIsReportedWithTheKeyAndTheReason() {
        ConfigValue result = ConfigRules.logCooldownSeconds(PATH, 0, 60);

        assertFalse(result.isValid());
        assertEquals(60, result.replacement());
        assertTrue(result.message().contains(PATH),
                "the warning has to name the key or the operator cannot find it");
        assertTrue(result.message().contains("unset"),
                "and has to say why, since 0 looks like it should mean something");
    }

    @Test
    void aRaisedCooldownNamesTheMinimum() {
        ConfigValue result = ConfigRules.logCooldownSeconds(PATH, 2, 60);

        assertFalse(result.isValid());
        assertEquals(ConfigRules.MIN_LOG_COOLDOWN_SECONDS, result.replacement());
        assertTrue(result.message().contains(String.valueOf(ConfigRules.MIN_LOG_COOLDOWN_SECONDS)),
                "the warning has to state the bound that was applied");
    }

    @Test
    void anAcceptableCooldownIsNotLogged() {
        ConfigValue result = ConfigRules.logCooldownSeconds(PATH, 120, 60);

        assertTrue(result.isValid());
        assertEquals(null, result.replacement(), "a valid value must not be overwritten");
        assertEquals(null, result.message(), "and must not log anything");
    }

    // -----------------------------------------------------------------------------------------
    // The settings record
    // -----------------------------------------------------------------------------------------

    @Test
    void theProductionDefaultsAreSilent() {
        SelfMonitorLogging defaults = SelfMonitorLogging.defaults();

        assertFalse(defaults.enabled(), "a fresh install prints nothing about its own budgets");
        assertFalse(defaults.recovery(), "recovery lines are opt-in even once logging is on");
        assertFalse(defaults.verbose());
        assertTrue(defaults.overBudget(),
                "but if you do turn logging on, warnings are what you turned it on for");
        assertEquals(60_000L, defaults.cooldownMillis());
    }

    @Test
    void theRecordAppliesTheSameCooldownRuleAsTheValidator() {
        // Two readers, one rule. If these ever diverged, config.yml would say one thing and the
        // running policy would do another.
        assertEquals(SelfMonitorLogging.DEFAULT_COOLDOWN_SECONDS * 1000L,
                SelfMonitorLogging.of(true, true, false, false, 0).cooldownMillis());
        assertEquals(ConfigRules.MIN_LOG_COOLDOWN_SECONDS * 1000L,
                SelfMonitorLogging.of(true, true, false, false, 1).cooldownMillis());
        assertEquals(120_000L,
                SelfMonitorLogging.of(true, true, false, false, 120).cooldownMillis());
    }

    @Test
    void cooldownSecondsRoundTripsTheMillisecondValue() {
        assertEquals(60L, SelfMonitorLogging.defaults().cooldownSeconds());
        assertEquals(120L, SelfMonitorLogging.of(true, true, false, false, 120).cooldownSeconds());
    }

    @Test
    void aNegativeMillisecondValueCannotBeConstructed() {
        // The canonical constructor is reachable directly from the loader and from tests.
        assertEquals(0L, new SelfMonitorLogging(true, true, false, false, -1L).cooldownMillis());
    }
}
