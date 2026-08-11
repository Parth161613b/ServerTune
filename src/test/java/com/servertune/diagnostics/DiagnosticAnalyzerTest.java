package com.servertune.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 1: the diagnosis must be useful. Section 4: it must report the age of its data and
 * never imply it scanned anything.
 */
class DiagnosticAnalyzerTest {

    private static final long NOW = 1_000_000L;

    private final DiagnosticAnalyzer analyzer =
            new DiagnosticAnalyzer(DiagnosticThresholds.defaults());

    private static DiagnosticSnapshot.Builder healthy() {
        return DiagnosticSnapshot.builder()
                .sampledAt(NOW)
                .tps(20.0)
                .mspt(12.0)
                .cpuUsage(30.0)
                .memoryUsed(1024L * 1024L * 512L)
                .memoryMax(1024L * 1024L * 4096L)
                .loadedChunks(400)
                .totalEntities(200);
    }

    @Test
    void healthyServerProducesNoFindings() {
        DiagnosticReport report = analyzer.analyze(healthy().build(), NOW);

        assertEquals(DiagnosticSeverity.OK, report.severity());
        assertTrue(report.findings().isEmpty(), "a healthy server must not be flagged");
        assertFalse(report.isEmpty());
    }

    @Test
    void nullSnapshotIsUnavailableRatherThanAnEmptyDiagnosis() {
        DiagnosticReport report = analyzer.analyze(null, NOW);

        assertTrue(report.isEmpty(), "no sample yet must be distinguishable from a clean bill");
        assertEquals(DiagnosticSeverity.OK, report.severity());
        assertTrue(report.hotspots().isEmpty());
    }

    /**
     * 50 ms is exactly one tick, so a server sitting at the tick
     * budget is healthy. Judging MSPT against the alerting values (40/50) would report 20 TPS
     * as degraded.
     */
    @Test
    void msptAtExactlyOneTickIsNotFlagged() {
        DiagnosticReport report = analyzer.analyze(healthy().mspt(50.0).build(), NOW);

        assertTrue(findings(report, "MSPT").isEmpty(),
                "50.00 ms is one full tick and must not be reported as a problem");
    }

    @Test
    void msptLadderEscalatesAboveTheTickBudget() {
        assertEquals(DiagnosticSeverity.LOW,
                only(analyzer.analyze(healthy().mspt(52.0).build(), NOW), "MSPT").severity());
        assertEquals(DiagnosticSeverity.MEDIUM,
                only(analyzer.analyze(healthy().mspt(60.0).build(), NOW), "MSPT").severity());
        assertEquals(DiagnosticSeverity.HIGH,
                only(analyzer.analyze(healthy().mspt(80.0).build(), NOW), "MSPT").severity());
    }

    @Test
    void severeTpsIsCriticalAndSetsTheOverallSeverity() {
        DiagnosticReport report = analyzer.analyze(healthy().tps(6.0).build(), NOW);

        assertEquals(DiagnosticSeverity.CRITICAL, only(report, "TPS").severity());
        assertEquals(DiagnosticSeverity.CRITICAL, report.severity(),
                "overall severity is the worst finding");
    }

    @Test
    void findingsAreOrderedWorstFirst() {
        DiagnosticReport report = analyzer.analyze(healthy()
                .tps(6.0)               // CRITICAL
                .forceLoadedChunks(3)   // LOW
                .cpuUsage(85.0)         // MEDIUM
                .build(), NOW);

        List<Finding> findings = report.findings();
        for (int i = 1; i < findings.size(); i++) {
            assertTrue(findings.get(i - 1).severity().atLeast(findings.get(i).severity()),
                    "findings must be sorted by descending severity");
        }
        assertEquals(DiagnosticSeverity.CRITICAL, findings.get(0).severity());
    }

    @Test
    void entityFindingCarriesTheBreakdownAsDetails() {
        DiagnosticReport report = analyzer.analyze(healthy()
                .totalEntities(9000)
                .itemCount(1200)
                .mobCount(3000)
                .villagerCount(40)
                .build(), NOW);

        Finding entities = findings(report, "Entities").stream()
                .filter(f -> f.summary().contains("9000 entities loaded"))
                .findFirst()
                .orElseThrow();

        assertEquals(DiagnosticSeverity.HIGH, entities.severity());
        assertTrue(entities.details().contains("1200 dropped items"));
        assertTrue(entities.details().contains("3000 mobs"));
        assertTrue(entities.details().contains("40 villagers"));
    }

    /** NOT_MEASURED must never be compared against a threshold as if it were a count. */
    @Test
    void unmeasuredBlockEntityCountsAreNotFlagged() {
        DiagnosticSnapshot s = healthy().build();

        assertFalse(s.hasHopperCount(), "hopper count defaults to not measured");
        assertFalse(s.hasBlockEntityCount());
        assertTrue(findings(analyzer.analyze(s, NOW), "Hoppers").isEmpty());
        assertTrue(findings(analyzer.analyze(s, NOW), "Block entities").isEmpty());
    }

    @Test
    void memoryIsSkippedWhenMaxHeapIsUnknown() {
        DiagnosticSnapshot s = healthy().memoryMax(0L).memoryUsed(999L).build();

        assertEquals(0.0, s.getMemoryUsedPercent());
        assertTrue(findings(analyzer.analyze(s, NOW), "Memory").isEmpty());
    }

    @Test
    void ageAndStalenessComeFromTheCallerSuppliedClock() {
        DiagnosticSnapshot s = healthy().build();

        DiagnosticReport fresh = analyzer.analyze(s, NOW + 5_000L);
        assertEquals(5L, fresh.ageSeconds());
        assertFalse(fresh.stale());

        DiagnosticReport old = analyzer.analyze(s, NOW + 45_000L);
        assertEquals(45L, old.ageSeconds());
        assertTrue(old.stale(), "past stale-after-seconds the report must say so");
    }

    @Test
    void hotspotsArePassedThroughUnchanged() {
        ChunkHotspot hotspot = ChunkHotspot.builder("world", 124, -37)
                .entityCount(82)
                .score(2.0)
                .build();

        DiagnosticReport report =
                analyzer.analyze(healthy().hotspots(List.of(hotspot)).build(), NOW);

        assertEquals(List.of(hotspot), report.hotspots());
    }

    private static List<Finding> findings(DiagnosticReport report, String category) {
        return report.findings().stream()
                .filter(f -> f.category().equals(category))
                .toList();
    }

    private static Finding only(DiagnosticReport report, String category) {
        List<Finding> matches = findings(report, category);
        assertEquals(1, matches.size(), "expected exactly one " + category + " finding");
        return matches.get(0);
    }
}
