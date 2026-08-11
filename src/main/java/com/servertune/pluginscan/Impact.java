package com.servertune.pluginscan;

/**
 * How much a plugin appears to have contributed to main-thread cost during the window.
 *
 * <p>Separate from {@link Confidence} on purpose: impact is "how big", confidence is "how sure".
 * Reporting one number that mixed them would let a precisely-measured trivial cost outrank a
 * roughly-observed large one, or the reverse.
 *
 * <p>The wording is deliberately observational. {@code /servertune diagnose plugins} measures a
 * ten-second window on a live server; it does not run a controlled experiment, so it can say
 * what it observed and cannot say what caused what. See {@link PluginScanRanker}.
 */
public enum Impact {

    /** Nothing measurable, and nothing correlated. Reported only when explicitly asked for. */
    NEGLIGIBLE,
    LOW,
    MEDIUM,
    HIGH;

    public String label() {
        return name();
    }
}
