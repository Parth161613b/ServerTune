package com.servertune.diagnostics;

import java.util.List;

/**
 * One observation about the server, with the measurement it came from.
 *
 * <p>A finding states what was measured. It deliberately does not state what caused it:
 * the plugin samples counters, it does not profile the tick loop, so it cannot attribute
 * a low TPS to any particular source. Where a plausible contributor is worth mentioning
 * it goes in {@link #details()} phrased as a possibility, never as a cause.
 *
 * @param severity how far from healthy the measurement is
 * @param category short label for grouping, e.g. "TPS", "Memory", "Entities"
 * @param summary  the measurement, as one line
 * @param details  supporting measurements; may be empty, never null
 */
public record Finding(DiagnosticSeverity severity,
                      String category,
                      String summary,
                      List<String> details) {

    public Finding(DiagnosticSeverity severity, String category, String summary,
                   List<String> details) {
        this.severity = severity;
        this.category = category;
        this.summary = summary;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static Finding of(DiagnosticSeverity severity, String category, String summary) {
        return new Finding(severity, category, summary, List.of());
    }

    public static Finding of(DiagnosticSeverity severity, String category, String summary,
                             String... details) {
        return new Finding(severity, category, summary, List.of(details));
    }
}
