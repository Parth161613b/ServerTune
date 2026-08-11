package com.servertune.pluginscan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 3 and Section 20: validate every value, and make an effectively infinite profiling
 * session impossible to configure.
 *
 * <p>The clamping lives in the record's compact constructor rather than only in the config
 * validator, so these tests cover the path a hand-edited config.yml takes even if it somehow
 * bypassed validation. That is the property worth pinning: not "the validator logs a warning",
 * but "no value on disk can produce an unbounded session".
 */
class PluginScanSettingsTest {

    @Test
    void defaultsMatchTheDocumentedConfiguration() {
        PluginScanSettings settings = PluginScanSettings.defaults();

        assertTrue(settings.enabled());
        assertEquals(10, settings.defaultDurationSeconds());
        assertEquals(30, settings.maxDurationSeconds());
        assertEquals(10, settings.topResults());
        assertEquals(Confidence.LOW, settings.minimumConfidence());
    }

    /**
     * The headline safety property. An administrator typing a day's worth of seconds gets the
     * compiled ceiling, not a day of wrapped listeners.
     */
    @Test
    void absurdMaxDurationIsCutToTheHardCeiling() {
        PluginScanSettings settings = new PluginScanSettings(true, 10, 86_400, 10,
                Confidence.LOW, 5);

        assertEquals(PluginScanSettings.HARD_MAX_DURATION_SECONDS, settings.maxDurationSeconds());
        assertEquals(60, settings.maxDurationSeconds(), "the ceiling is compiled, not configured");
    }

    @Test
    void integerOverflowAttemptStillLandsInsideTheCeiling() {
        assertEquals(PluginScanSettings.HARD_MAX_DURATION_SECONDS,
                PluginScanSettings.clampMax(Integer.MAX_VALUE));
    }

    /** Zero means "unset" here, matching the log-cooldown convention already in the project. */
    @Test
    void zeroOrNegativeMaxDurationResolvesToTheDefault() {
        assertEquals(PluginScanSettings.DEFAULT_MAX_DURATION_SECONDS,
                PluginScanSettings.clampMax(0));
        assertEquals(PluginScanSettings.DEFAULT_MAX_DURATION_SECONDS,
                PluginScanSettings.clampMax(-1));
    }

    @Test
    void maxDurationBelowTheFloorIsRaisedToIt() {
        assertEquals(PluginScanSettings.MIN_DURATION_SECONDS, PluginScanSettings.clampMax(1));
    }

    /**
     * A default duration larger than the configured maximum is a contradiction in config.yml.
     * The maximum wins - it is the safety setting.
     */
    @Test
    void defaultDurationNeverExceedsTheMaximum() {
        PluginScanSettings settings = new PluginScanSettings(true, 45, 20, 10, Confidence.LOW, 5);

        assertEquals(20, settings.maxDurationSeconds());
        assertEquals(20, settings.defaultDurationSeconds());
    }

    @Test
    void topResultsIsBoundedAndZeroMeansUnset() {
        assertEquals(PluginScanSettings.DEFAULT_TOP_RESULTS, PluginScanSettings.clampTopResults(0));
        assertEquals(PluginScanSettings.MAX_TOP_RESULTS,
                PluginScanSettings.clampTopResults(5_000));
        assertEquals(7, PluginScanSettings.clampTopResults(7));
    }

    @Test
    void progressIntervalNeverDropsToZero() {
        PluginScanSettings settings = new PluginScanSettings(true, 10, 30, 10, Confidence.LOW, 0);

        assertEquals(1, settings.progressIntervalSeconds(),
                "a zero interval would make the modulo check in the ticker match every second");
    }

    @Test
    void unparseableConfidenceFallsBackRatherThanThrowing() {
        assertEquals(Confidence.LOW, PluginScanSettings.parseConfidence("nonsense"));
        assertEquals(Confidence.LOW, PluginScanSettings.parseConfidence(null));
        assertEquals(Confidence.HIGH, PluginScanSettings.parseConfidence("  high  "));
        assertEquals(Confidence.MEDIUM, PluginScanSettings.parseConfidence("Medium"));
    }

    @Test
    void nullConfidenceInTheRecordResolvesToTheDefault() {
        PluginScanSettings settings = new PluginScanSettings(true, 10, 30, 10, null, 5);

        assertEquals(PluginScanSettings.DEFAULT_MINIMUM_CONFIDENCE, settings.minimumConfidence());
    }

    /** What the command actually calls: an operator-supplied window, clamped. */
    @Test
    void requestedDurationIsClampedToTheConfiguredMaximum() {
        PluginScanSettings settings = new PluginScanSettings(true, 10, 30, 10, Confidence.LOW, 5);

        assertEquals(25, settings.resolveDuration(25));
        assertEquals(30, settings.resolveDuration(120), "over the configured max");
        assertEquals(PluginScanSettings.MIN_DURATION_SECONDS, settings.resolveDuration(1));
    }

    @Test
    void omittedDurationUsesTheConfiguredDefault() {
        PluginScanSettings settings = new PluginScanSettings(true, 12, 30, 10, Confidence.LOW, 5);

        assertEquals(12, settings.resolveDuration(0));
        assertEquals(12, settings.resolveDuration(-5));
    }

    /** Even with the ceiling itself misconfigured, a request cannot escape the compiled bound. */
    @Test
    void requestCannotEscapeTheCompiledCeilingViaAMisconfiguredMaximum() {
        PluginScanSettings settings = new PluginScanSettings(true, 10, Integer.MAX_VALUE, 10,
                Confidence.LOW, 5);

        assertTrue(settings.resolveDuration(Integer.MAX_VALUE)
                <= PluginScanSettings.HARD_MAX_DURATION_SECONDS);
    }
}
