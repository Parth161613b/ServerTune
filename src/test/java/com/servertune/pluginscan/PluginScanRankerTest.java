package com.servertune.pluginscan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sections 6, 13, 16 and 17: rank on observed evidence, never on plugin name or task count, and
 * never claim causation.
 *
 * <p>These are the tests that matter most for this feature. The implementation could be wrong
 * about a millisecond and nobody would be harmed; if it tells an operator that a plugin is
 * <em>causing</em> lag on the strength of owning some tasks, they will delete a working plugin
 * from a production server. Each test below pins one claim the ranker is not allowed to make.
 */
class PluginScanRankerTest {

    private final PluginScanRanker ranker = new PluginScanRanker(PluginScanSettings.defaults());

    private static PluginActivity.Measurement timed(String name, double syncMillis, long calls) {
        return new PluginActivity.Measurement(name, syncMillis,
                calls == 0L ? 0.0 : syncMillis / calls, syncMillis / Math.max(1L, calls) * 2.0,
                calls, 0.0, 0L, 0, 0);
    }

    private static PluginActivity.Measurement tasksOnly(String name, int sync, int async) {
        return new PluginActivity.Measurement(name, 0.0, 0.0, 0.0, 0L, 0.0, 0L, sync, async);
    }

    private static PluginActivity.Measurement asyncOnly(String name, double asyncMillis,
                                                        long calls) {
        return new PluginActivity.Measurement(name, 0.0, 0.0, 0.0, 0L, asyncMillis, calls, 0, 0);
    }

    private static PluginFinding byName(List<PluginFinding> findings, String name) {
        return findings.stream().filter(f -> f.pluginName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " missing from " + findings));
    }

    @Test
    void nothingObservedProducesNoFindings() {
        assertTrue(ranker.rank(List.of(), false).isEmpty());
        assertTrue(ranker.rank(null, false).isEmpty());
    }

    /**
     * Section 17, stated negatively. A plugin that owns a hundred tasks and was never seen doing
     * any main-thread work must not outrank a plugin that was measured being expensive.
     */
    @Test
    void taskCountNeverOutranksMeasuredCost() {
        List<PluginFinding> ranked = ranker.rank(List.of(
                tasksOnly("TaskHeavy", 80, 20),
                timed("MeasuredCost", 40.0, 100)), false);

        assertEquals("MeasuredCost", ranked.get(0).pluginName());
        assertEquals("TaskHeavy", ranked.get(1).pluginName());
    }

    @Test
    void taskOwnershipIsAlwaysLowConfidenceAndSaysWhy() {
        PluginFinding finding = byName(ranker.rank(List.of(tasksOnly("TaskHeavy", 100, 0)), false),
                "TaskHeavy");

        assertEquals(Confidence.LOW, finding.confidence());
        assertTrue(finding.reason().contains("not evidence of cost"),
                "the report must say what task ownership does not prove: " + finding.reason());
        assertEquals(0.0, PluginScanRanker.score(finding.measurement()), 1e-9,
                "task count must contribute nothing to the score");
    }

    /** Section 13. Async work is reported but scored zero, so it cannot rank a plugin up. */
    @Test
    void asyncMillisecondsAreNeverScored() {
        PluginActivity.Measurement heavyAsync = asyncOnly("AsyncIO", 5_000.0, 500);

        assertEquals(0.0, PluginScanRanker.score(heavyAsync), 1e-9);

        List<PluginFinding> ranked = ranker.rank(List.of(heavyAsync, timed("Sync", 20.0, 50)),
                false);

        assertEquals("Sync", ranked.get(0).pluginName());
    }

    /**
     * An async-only plugin has no timed main-thread evidence and no tasks, so there is nothing
     * honest to say about it as a source of tick cost. It is dropped rather than listed at zero.
     */
    @Test
    void asyncOnlyPluginWithNoTasksIsNotAFinding() {
        assertTrue(ranker.rank(List.of(asyncOnly("AsyncIO", 5_000.0, 500)), false).isEmpty());
    }

    @Test
    void asyncActivityIsLabelledSeparatelyWhenThePluginAlsoRanSync() {
        PluginActivity.Measurement mixed = new PluginActivity.Measurement("Mixed", 30.0, 0.6, 2.0,
                50L, 900.0, 40L, 0, 0);

        PluginFinding finding = byName(ranker.rank(List.of(mixed), false), "Mixed");

        assertTrue(finding.reason().contains("asynchronous"), finding.reason());
        assertTrue(finding.reason().contains("do not delay the tick"), finding.reason());
    }

    /** Impact bands are shares of measured handler time, and the dominant plugin gets HIGH. */
    @Test
    void dominantShareOfMeasuredTimeReadsAsHighImpact() {
        List<PluginFinding> ranked = ranker.rank(List.of(
                timed("Dominant", 80.0, 200),
                timed("Minor", 20.0, 200)), false);

        assertEquals(Impact.HIGH, byName(ranked, "Dominant").impact());
        assertEquals(Impact.MEDIUM, byName(ranked, "Minor").impact());
    }

    /**
     * The absolute floor. On a quiet window a plugin can hold 100% of a trivial total; without a
     * floor it would be reported as the server's biggest problem for costing 0.3 ms.
     */
    @Test
    void largeShareOfATinyTotalIsStillNegligible() {
        PluginFinding finding = byName(ranker.rank(List.of(timed("Tiny", 0.3, 60)), false), "Tiny");

        assertEquals(Impact.NEGLIGIBLE, finding.impact());
        assertFalse(finding.isSignificant(),
                "a 0.3 ms total must not become a 'potential performance source'");
    }

    @Test
    void confidenceRisesWithSampleSize() {
        long thin = PluginScanRanker.STABLE_SAMPLE_CALLS - 1;
        long thick = PluginScanRanker.STABLE_SAMPLE_CALLS;

        assertEquals(Confidence.MEDIUM,
                byName(ranker.rank(List.of(timed("Thin", 10.0, thin)), false), "Thin")
                        .confidence());
        assertEquals(Confidence.HIGH,
                byName(ranker.rank(List.of(timed("Thick", 10.0, thick)), false), "Thick")
                        .confidence());
    }

    @Test
    void thinSampleSaysSoInsteadOfPresentingAStableAverage() {
        PluginFinding finding = byName(
                ranker.rank(List.of(timed("Thin", 10.0, 3)), false), "Thin");

        assertTrue(finding.reason().contains("thin sample"), finding.reason());
    }

    /** Section 6. Every confidence level has to explain the evidence behind it. */
    @Test
    void everyFindingExplainsItsEvidence() {
        List<PluginFinding> ranked = ranker.rank(List.of(
                timed("Measured", 40.0, 200), tasksOnly("Scheduled", 5, 5)), false);

        for (PluginFinding finding : ranked) {
            assertFalse(finding.observation().isBlank(), finding.pluginName() + " observation");
            assertFalse(finding.reason().isBlank(), finding.pluginName() + " reason");
        }
        assertTrue(byName(ranked, "Measured").reason().contains("Measured directly by timing"));
    }

    /**
     * Section 16, the central prohibition. Elevated MSPT sharpens the wording for a plugin that
     * was already measurably expensive, and the sentence it adds explicitly denies causation.
     */
    @Test
    void elevatedMsptAddsCorrelationWordingThatDeniesCausation() {
        PluginFinding finding = byName(ranker.rank(List.of(timed("Busy", 40.0, 200)), true),
                "Busy");

        assertTrue(finding.msptCorrelated());
        assertTrue(finding.reason().contains("correlation, not proof of cause"),
                finding.reason());
    }

    /** Correlation may sharpen a finding. It may never manufacture one. */
    @Test
    void elevatedMsptAloneCreatesNoFinding() {
        assertTrue(ranker.rank(List.of(
                new PluginActivity.Measurement("Idle", 0.0, 0.0, 0.0, 0L, 0.0, 0L, 0, 0)),
                true).isEmpty());

        PluginFinding negligible = byName(ranker.rank(List.of(timed("Tiny", 0.2, 40)), true),
                "Tiny");
        assertFalse(negligible.msptCorrelated(),
                "a negligible plugin must not be dressed up because the server was slow");
    }

    @Test
    void impactAndConfidenceAreIndependentAxes() {
        // Large cost, thin sample: big impact, but only medium confidence in the average.
        PluginFinding finding = byName(ranker.rank(List.of(timed("Spiky", 90.0, 4)), false),
                "Spiky");

        assertEquals(Impact.HIGH, finding.impact());
        assertEquals(Confidence.MEDIUM, finding.confidence());
    }

    @Test
    void minimumConfidenceFiltersLowEvidenceFindings() {
        PluginScanSettings strict = new PluginScanSettings(true, 10, 30, 10, Confidence.HIGH, 5);
        List<PluginFinding> ranked = new PluginScanRanker(strict).rank(List.of(
                timed("Thick", 40.0, 200),
                timed("Thin", 30.0, 2),
                tasksOnly("Scheduled", 9, 0)), false);

        assertEquals(1, ranked.size());
        assertEquals("Thick", ranked.get(0).pluginName());
    }

    @Test
    void topResultsCapsTheList() {
        PluginScanSettings capped = new PluginScanSettings(true, 10, 30, 2, Confidence.LOW, 5);
        List<PluginFinding> ranked = new PluginScanRanker(capped).rank(List.of(
                timed("A", 40.0, 100), timed("B", 30.0, 100),
                timed("C", 20.0, 100), timed("D", 10.0, 100)), false);

        assertEquals(2, ranked.size());
        assertEquals("A", ranked.get(0).pluginName());
        assertEquals("B", ranked.get(1).pluginName());
    }

    @Test
    void rankingIsOrderedByMeasuredMainThreadCost() {
        List<PluginFinding> ranked = ranker.rank(List.of(
                timed("Small", 5.0, 100),
                timed("Large", 50.0, 100),
                timed("Middle", 25.0, 100)), false);

        assertEquals(List.of("Large", "Middle", "Small"),
                ranked.stream().map(PluginFinding::pluginName).toList());
    }

    /** Nothing about a plugin's name may influence its position. */
    @Test
    void identicalMeasurementsRankIdenticallyRegardlessOfName() {
        List<PluginFinding> ranked = ranker.rank(List.of(
                timed("Zzz", 40.0, 100), timed("Aaa", 40.0, 100)), false);

        assertEquals(ranked.get(0).impact(), ranked.get(1).impact());
        assertEquals(ranked.get(0).confidence(), ranked.get(1).confidence());
    }

    /** Section 16. The only action ServerTune will suggest is a controlled staging test. */
    @Test
    void recommendationNeverSaysToDisableAProductionPlugin() {
        PluginFinding finding = byName(ranker.rank(List.of(timed("Busy", 40.0, 200)), true),
                "Busy");

        String recommendation = finding.recommendation();
        assertTrue(recommendation.contains("staging"), recommendation);
        assertTrue(recommendation.contains("verify whether MSPT improves"), recommendation);
        assertEquals("potential contributor to main-thread workload", finding.descriptor());
        assertNotEquals("cause of lag", finding.descriptor());
    }

    @Test
    void nullSettingsFallBackToDefaultsRatherThanThrowing() {
        assertEquals(1, new PluginScanRanker(null)
                .rank(List.of(timed("Example", 40.0, 100)), false).size());
    }
}
