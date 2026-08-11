package com.servertune.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens when config.yml contains something impossible.
 *
 * <p>The rule that matters most is the one asserted by
 * {@link #everyCorrectionKeepsTheServerRunnable()}: a mistyped number must never stop the plugin
 * loading. An owner whose server will not start because they typed {@code -1} is worse off than
 * one whose server starts on the default and logs a warning telling them where to look.
 *
 * <p>{@link ConfigValidator} itself needs a {@code FileConfiguration}, so what is asserted here
 * is the part that decides. The validator is a thin translation: it reads each path, calls the
 * matching rule, and for any result that is not valid logs {@code message()} and writes
 * {@code replacement()} back.
 */
class ConfigRulesTest {

    @Test
    void theFourShippedProfilesAreAccepted() {
        for (PerformanceProfile profile : PerformanceProfile.values()) {
            assertTrue(ConfigRules.isValidProfile(profile.name().toLowerCase()),
                    profile + " is a real profile and must validate");
        }
    }

    @Test
    void profileNamesAreCaseAndWhitespaceInsensitive() {
        assertTrue(ConfigRules.isValidProfile("Balanced"));
        assertTrue(ConfigRules.isValidProfile("BALANCED"));
        assertTrue(ConfigRules.isValidProfile("  balanced  "),
                "a stray space in a hand-edited file is not a mistake worth punishing");
    }

    @Test
    void anUnknownProfileFallsBackToBalanced() {
        ConfigValue result = ConfigRules.profile("performance.profile", "turbo", "balanced");

        assertFalse(result.isValid());
        assertEquals("balanced", result.replacement());
        assertTrue(result.message().contains("turbo"),
                "the warning has to name the bad value or the owner cannot find it");
    }

    @Test
    void aMissingProfileFallsBackRatherThanThrowing() {
        ConfigValue result = ConfigRules.profile("performance.profile", null, "balanced");

        assertFalse(result.isValid());
        assertEquals("balanced", result.replacement());
    }

    @Test
    void aValidValueCarriesNoReplacement() {
        ConfigValue result = ConfigRules.positive("performance.intervals.health", 100, 100);

        assertTrue(result.isValid());
        assertNull(result.replacement(), "a valid value must not be overwritten");
        assertNull(result.message(), "and must not log anything");
    }

    @Test
    void zeroIntervalIsRejected() {
        // A period of zero makes the scheduler run the module every tick.
        ConfigValue result = ConfigRules.positive("performance.intervals.modules.hopper", 0, 400);

        assertFalse(result.isValid());
        assertEquals(400, result.replacement());
    }

    @Test
    void negativeIntervalIsRejected() {
        ConfigValue result = ConfigRules.positive("performance.intervals.deep-analysis", -1, 1200);

        assertFalse(result.isValid());
        assertEquals(1200, result.replacement());
    }

    @Test
    void negativeMsptThresholdIsRejected() {
        ConfigValue result = ConfigRules.positive("alerts.mspt.warning.threshold", -5.0, 40.0);

        assertFalse(result.isValid());
        assertEquals(40.0, result.replacement());
    }

    @Test
    void anMsptThresholdHasNoUpperBound() {
        // A badly stalled server really can sit at 2000ms, so this is a legitimate setting.
        assertTrue(ConfigRules.positive("alerts.mspt.critical.threshold", 2000.0, 50.0).isValid());
    }

    @Test
    void aTpsThresholdAboveTwentyIsRejected() {
        // 20 TPS is the server ceiling, so 25 could never fire.
        ConfigValue result = ConfigRules.tpsThreshold("alerts.tps.warning.threshold", 25.0, 18.0);

        assertFalse(result.isValid());
        assertEquals(18.0, result.replacement());
        assertTrue(result.message().contains("20.0"),
                "the warning has to state the bound that was exceeded");
    }

    @Test
    void aNegativeTpsThresholdIsRejected() {
        ConfigValue result = ConfigRules.tpsThreshold("fallback.trigger.tps", -1.0, 10.0);

        assertFalse(result.isValid());
        assertEquals(10.0, result.replacement());
    }

    @Test
    void bothEndsOfTheTpsRangeAreAccepted() {
        assertTrue(ConfigRules.tpsThreshold("fallback.trigger.tps",
                        ConfigRules.MIN_TPS, 10.0).isValid(),
                "0 TPS means a frozen server, which is a usable trigger");
        assertTrue(ConfigRules.tpsThreshold("fallback.recovery.tps",
                        ConfigRules.MAX_TPS, 18.0).isValid(),
                "20 TPS is the ceiling and a legitimate recovery target");
    }

    @Test
    void everyCorrectionKeepsTheServerRunnable() {
        // The whole point of correcting rather than rejecting: whatever the operator typed,
        // there is always a usable value to carry on with.
        ConfigValue[] corrections = {
                ConfigRules.profile("performance.profile", "nonsense", "balanced"),
                ConfigRules.positive("performance.intervals.health", -100, 100),
                ConfigRules.positive("alerts.mspt.warning.threshold", 0.0, 40.0),
                ConfigRules.tpsThreshold("alerts.tps.critical.threshold", 99.0, 15.0),
        };

        for (ConfigValue correction : corrections) {
            assertFalse(correction.isValid(), correction.path() + " should have been corrected");
            assertNotNull(correction.replacement(),
                    correction.path() + " must be given a usable value, not left broken");
            assertNotNull(correction.message(),
                    correction.path() + " must be logged so the owner can find it");
            assertTrue(correction.message().contains(correction.path()),
                    "the warning has to name the path it corrected");
        }
    }

    @Test
    void theCorrectedDefaultsAreThemselvesValid() {
        // A replacement that would fail its own rule would leave the plugin misconfigured
        // after "correction", so check the shipped defaults round-trip.
        assertTrue(ConfigRules.positive("performance.intervals.health", 100, 100).isValid());
        assertTrue(ConfigRules.tpsThreshold("alerts.tps.warning.threshold", 18.0, 18.0).isValid());
        assertTrue(ConfigRules.tpsThreshold("alerts.tps.critical.threshold", 15.0, 15.0).isValid());
        assertTrue(ConfigRules.tpsThreshold("alerts.tps.emergency.threshold", 10.0, 10.0).isValid());
        assertTrue(ConfigRules.isValidProfile("balanced"));
    }
}
