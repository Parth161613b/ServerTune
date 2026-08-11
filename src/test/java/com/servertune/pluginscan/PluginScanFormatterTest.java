package com.servertune.pluginscan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sections 14, 15 and 16: the report says what was measured, says what was not, and never
 * overstates either.
 *
 * <p>Wording is worth testing here because the wording <em>is</em> the deliverable. A number that
 * is 3% off harms nobody; a sentence that turns a ten-second correlation into a diagnosis gets a
 * working plugin deleted from a production server. Each test below fixes one sentence the
 * formatter must or must not produce.
 */
class PluginScanFormatterTest {

    private static PluginScanReport.Builder base(PluginScanReport.Outcome outcome) {
        return PluginScanReport.builder(outcome)
                .elapsedSeconds(10.0)
                .requestedSeconds(10)
                .tick(19.4, 44.5, 88.2)
                .world(6, 1_100, 2_400)
                .plugins(38, 36)
                .instrumentedEventCount(7);
    }

    private static PluginFinding finding(String name, double syncMillis, long calls, Impact impact,
                                         Confidence confidence, boolean correlated) {
        PluginActivity.Measurement m = new PluginActivity.Measurement(name, syncMillis,
                syncMillis / calls, syncMillis / calls * 3.0, calls, 0.0, 0L, 0, 0);
        return new PluginFinding(m, impact, confidence, "observed something", "because reasons",
                correlated);
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    private static boolean containsLineWith(List<String> lines, String fragment) {
        return lines.stream().anyMatch(line -> line.contains(fragment));
    }

    @Test
    void startedLineNamesTheWindowItWillActuallyRunFor() {
        assertTrue(PluginScanFormatter.startedLine(10)
                .contains("ServerTune plugin diagnostic started"));
        assertTrue(PluginScanFormatter.startedLine(10).contains("10 seconds"));
    }

    /** Section 7: elapsed / total, and nothing per tick. */
    @Test
    void progressLineShowsElapsedOverTotal() {
        String line = PluginScanFormatter.progressLine(5, 10);

        assertTrue(line.contains("Plugin diagnostic running"), line);
        assertTrue(line.contains("5"), line);
        assertTrue(line.contains("10"), line);
    }

    @Test
    void completedReportCarriesEveryBaselineFigure() {
        List<String> lines = PluginScanFormatter.render(base(PluginScanReport.Outcome.COMPLETED)
                .measuredPluginCount(3)
                .findings(List.of(finding("Busy", 40.0, 200, Impact.HIGH, Confidence.HIGH, false)))
                .build());
        String text = joined(lines);

        assertTrue(text.contains("Average TPS"), text);
        assertTrue(text.contains("19.40"), text);
        assertTrue(text.contains("Average MSPT"), text);
        assertTrue(text.contains("44.50"), text);
        assertTrue(text.contains("Peak MSPT"), text);
        assertTrue(text.contains("88.20"), text);
        assertTrue(text.contains("Players"), text);
        assertTrue(text.contains("Chunks"), text);
        assertTrue(text.contains("Entities"), text);
    }

    @Test
    void everyReportEndsBySayingProfilingHasStopped() {
        for (PluginScanReport.Outcome outcome : PluginScanReport.Outcome.values()) {
            List<String> lines = PluginScanFormatter.render(base(outcome).build());

            assertTrue(containsLineWith(lines, "Plugin profiling has stopped")
                            || containsLineWith(lines, "plugin profiling has stopped"),
                    outcome + " must state that profiling stopped: " + joined(lines));
        }
    }

    /** Section 15, verbatim in intent: silence is not a clean bill of health. */
    @Test
    void emptyReportRefusesToClearTheRemainingPlugins() {
        List<String> lines = PluginScanFormatter.render(base(PluginScanReport.Outcome.COMPLETED)
                .measuredPluginCount(12)
                .findings(List.of())
                .build());
        String text = joined(lines);

        assertTrue(text.contains("No significant plugin performance source was detected"), text);
        assertTrue(text.contains("NOT"), text);
        assertTrue(text.contains("prove that all plugins are"), text);
        assertTrue(text.contains("performance-safe"), text);
    }

    /** Section 21. */
    @Test
    void abortedReportLeadsWithWhyItStopped() {
        List<String> lines = PluginScanFormatter.render(
                base(PluginScanReport.Outcome.ABORTED_CRITICAL).build());

        assertTrue(containsLineWith(lines,
                        "Plugin diagnostic aborted because server performance became critical"),
                joined(lines));
    }

    @Test
    void cancelledReportSaysItIsPartial() {
        List<String> lines = PluginScanFormatter.render(
                base(PluginScanReport.Outcome.CANCELLED).build());

        assertTrue(containsLineWith(lines, "cancelled"), joined(lines));
        assertTrue(containsLineWith(lines, "observed before it stopped"), joined(lines));
    }

    /** Section 14: the per-finding fields, and only fields that were actually measured. */
    @Test
    void findingShowsImpactConfidenceAndTheObservedNumbers() {
        List<String> lines = PluginScanFormatter.render(base(PluginScanReport.Outcome.COMPLETED)
                .measuredPluginCount(4)
                .findings(List.of(finding("Busy", 40.0, 200, Impact.HIGH, Confidence.HIGH, true)))
                .build());
        String text = joined(lines);

        assertTrue(text.contains("Busy"), text);
        assertTrue(text.contains("Impact:"), text);
        assertTrue(text.contains("HIGH"), text);
        assertTrue(text.contains("Confidence:"), text);
        assertTrue(text.contains("Observed activity:"), text);
        assertTrue(text.contains("Average observed execution:"), text);
        assertTrue(text.contains("Peak observed execution:"), text);
        assertTrue(text.contains("Observed executions:"), text);
    }

    /**
     * Section 6, negatively. A task-only finding has no timed numbers, so the formatter must not
     * print an average execution time of 0.000 ms as though it had measured one.
     */
    @Test
    void taskOnlyFindingPrintsNoFabricatedTimings() {
        PluginActivity.Measurement tasksOnly =
                new PluginActivity.Measurement("Scheduled", 0.0, 0.0, 0.0, 0L, 0.0, 0L, 6, 3);
        List<String> lines = PluginScanFormatter.render(base(PluginScanReport.Outcome.COMPLETED)
                .measuredPluginCount(1)
                .findings(List.of(new PluginFinding(tasksOnly, Impact.LOW, Confidence.LOW,
                        "owns 9 scheduled task(s)", "ownership is not cost", false)))
                .build());
        String text = joined(lines);

        assertFalse(text.contains("Average observed execution"),
                "no timed evidence means no timing line: " + text);
        assertFalse(text.contains("Observed executions"), text);
        assertTrue(text.contains("Scheduled tasks owned"), text);
        assertTrue(text.contains("ownership is not a cost measurement"), text);
    }

    /** Section 16. Nothing in a rendered report may read as a diagnosis. */
    @Test
    void reportNeverClaimsCausation() {
        List<String> lines = PluginScanFormatter.render(base(PluginScanReport.Outcome.COMPLETED)
                .measuredPluginCount(4)
                .findings(List.of(finding("Busy", 40.0, 200, Impact.HIGH, Confidence.HIGH, true)))
                .build());
        String text = joined(lines);

        assertTrue(text.contains("potential contributor to main-thread workload"), text);
        assertTrue(text.contains("not"), text);
        assertTrue(text.contains("proof of cause"), text);
        assertTrue(text.contains("staging"), text);
        assertFalse(text.toLowerCase().contains("is causing"), text);
        assertFalse(text.toLowerCase().contains("responsible for the lag"), text);
    }

    /** The standing caveat: an unmeasured plugin has not been cleared. */
    @Test
    void everyReportStatesWhatWasNotMeasured() {
        PluginScanReport report = base(PluginScanReport.Outcome.COMPLETED).build();

        assertTrue(report.unmeasuredNote().contains("7 high-frequency event type(s)"),
                report.unmeasuredNote());
        assertTrue(report.unmeasuredNote().contains("was not cleared"), report.unmeasuredNote());
        assertTrue(containsLineWith(PluginScanFormatter.render(report), "was not cleared"));
    }

    /** Section 23: the profiler admits to its own cost, but only when there is a cost to admit. */
    @Test
    void overheadIsReportedOnlyWhenItIsWorthReporting() {
        PluginScanReport quiet = base(PluginScanReport.Outcome.COMPLETED)
                .overheadMillis(0.4).build();
        PluginScanReport costly = base(PluginScanReport.Outcome.COMPLETED)
                .overheadMillis(12.5).build();

        assertFalse(quiet.overheadWorthReporting());
        assertTrue(costly.overheadWorthReporting());
        assertFalse(containsLineWith(PluginScanFormatter.render(quiet), "profiling overhead"));
        assertTrue(containsLineWith(PluginScanFormatter.render(costly),
                "profiling overhead during this window measured"));
    }

    /** Negligible findings are measured, counted and deliberately not listed as sources. */
    @Test
    void negligibleFindingsAreNotListedAsPerformanceSources() {
        PluginScanReport report = base(PluginScanReport.Outcome.COMPLETED)
                .measuredPluginCount(2)
                .findings(List.of(
                        finding("Tiny", 0.2, 40, Impact.NEGLIGIBLE, Confidence.HIGH, false),
                        finding("Busy", 40.0, 200, Impact.HIGH, Confidence.HIGH, false)))
                .build();

        assertEquals(1, report.significantFindings().size());
        assertFalse(report.isEmpty());
        assertFalse(joined(PluginScanFormatter.render(report)).contains("Tiny"));
    }

    @Test
    void findingsListIsDefensivelyCopied() {
        List<PluginFinding> mutable = new java.util.ArrayList<>();
        mutable.add(finding("Busy", 40.0, 200, Impact.HIGH, Confidence.HIGH, false));
        PluginScanReport report = base(PluginScanReport.Outcome.COMPLETED)
                .findings(mutable).build();

        mutable.clear();

        assertEquals(1, report.findings().size(), "a report must be frozen once built");
    }

    @Test
    void nullFindingsBecomeAnEmptyReportRatherThanAnException() {
        PluginScanReport report = base(PluginScanReport.Outcome.COMPLETED).findings(null).build();

        assertTrue(report.isEmpty());
        assertTrue(containsLineWith(PluginScanFormatter.render(report),
                "No significant plugin performance source was detected"));
    }
}
