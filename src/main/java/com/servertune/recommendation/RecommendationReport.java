package com.servertune.recommendation;

import com.servertune.diagnostics.DiagnosticSnapshot;

import java.util.List;

/**
 * The result of one recommendation pass.
 *
 * <p>Carries the snapshot's age so the command can print "Data sampled X seconds ago."
 * rather than implying the numbers are live.
 */
public record RecommendationReport(DiagnosticSnapshot snapshot,
                                   List<Recommendation> recommendations,
                                   long ageMillis,
                                   boolean stale) {

    public RecommendationReport(DiagnosticSnapshot snapshot,
                                List<Recommendation> recommendations,
                                long ageMillis, boolean stale) {
        this.snapshot = snapshot;
        this.recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        this.ageMillis = ageMillis;
        this.stale = stale;
    }

    public boolean isEmpty() {
        return snapshot == null;
    }

    public long ageSeconds() {
        return ageMillis / 1000L;
    }

    public boolean hasRecommendations() {
        return !recommendations.isEmpty();
    }

    public static RecommendationReport unavailable() {
        return new RecommendationReport(null, List.of(), 0L, false);
    }
}
