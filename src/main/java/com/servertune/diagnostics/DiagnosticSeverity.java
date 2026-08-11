package com.servertune.diagnostics;

/**
 * How serious a diagnostic finding is.
 *
 * <p>Ordered from least to most serious so {@link #atLeast(DiagnosticSeverity)} and
 * {@link #max(DiagnosticSeverity, DiagnosticSeverity)} can compare by ordinal.
 */
public enum DiagnosticSeverity {

    /** Nothing worth reporting. */
    OK,

    /** Worth noting, no action implied. */
    LOW,

    /** Measurably off a healthy baseline. */
    MEDIUM,

    /** Clearly degraded. */
    HIGH,

    /** Severely degraded. */
    CRITICAL;

    public boolean atLeast(DiagnosticSeverity other) {
        return ordinal() >= other.ordinal();
    }

    public static DiagnosticSeverity max(DiagnosticSeverity a, DiagnosticSeverity b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
