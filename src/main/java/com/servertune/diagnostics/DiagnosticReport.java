package com.servertune.diagnostics;

import java.util.List;

/**
 * The result of one diagnosis: what was measured, how stale it was, and nothing that was
 * not measured.
 *
 * <p>Recommendations are NOT part of this. Diagnosis reports the server's condition;
 * {@code /optimizer recommendations} suggests what an owner might change. Mixing them made
 * the old engine emit advice like "enable aggressive optimization" in the middle of a
 * health readout, which is both a different question and one the owner did not ask.
 *
 * @param snapshot   the frozen measurements this was computed from
 * @param findings   observations, most severe first
 * @param hotspots   potential hotspots, most notable first; never described as causes
 * @param severity   the worst finding's severity
 * @param ageMillis  how old the snapshot was when the diagnosis ran
 * @param stale      whether {@code ageMillis} exceeded the configured staleness limit
 */
public record DiagnosticReport(DiagnosticSnapshot snapshot,
                               List<Finding> findings,
                               List<ChunkHotspot> hotspots,
                               DiagnosticSeverity severity,
                               long ageMillis,
                               boolean stale) {

    public DiagnosticReport(DiagnosticSnapshot snapshot, List<Finding> findings,
                            List<ChunkHotspot> hotspots, DiagnosticSeverity severity,
                            long ageMillis, boolean stale) {
        this.snapshot = snapshot;
        this.findings = findings == null ? List.of() : List.copyOf(findings);
        this.hotspots = hotspots == null ? List.of() : List.copyOf(hotspots);
        this.severity = severity;
        this.ageMillis = ageMillis;
        this.stale = stale;
    }

    /** True when there is no snapshot to diagnose yet. */
    public boolean isEmpty() {
        return snapshot == null;
    }

    public long ageSeconds() {
        return ageMillis / 1000L;
    }

    public List<Finding> findingsAtLeast(DiagnosticSeverity minimum) {
        return findings.stream().filter(f -> f.severity().atLeast(minimum)).toList();
    }

    /** No snapshot available - the caller should say so rather than print zeroes. */
    public static DiagnosticReport unavailable() {
        return new DiagnosticReport(null, List.of(), List.of(),
                DiagnosticSeverity.OK, 0L, false);
    }
}
