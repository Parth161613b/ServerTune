package com.servertune.pluginscan;

import com.servertune.config.ConfigRules;

/**
 * The validated settings for one on-demand plugin diagnostic.
 *
 * <p>Bukkit-free, so the duration clamping - the part that decides whether an operator can
 * accidentally profile the server for an hour - is reachable from a unit test. paper-api is
 * {@code compileOnly} and is not on the test classpath.
 *
 * <p><b>The duration rules are the whole point of this class.</b> A profiling session mutates
 * other plugins' listener registrations for as long as it runs, so "how long" is a safety
 * setting rather than a preference:
 * <ul>
 *   <li>{@link #HARD_MAX_DURATION_SECONDS} is a compile-time ceiling that config.yml cannot
 *       raise. An operator who writes {@code max-duration-seconds: 86400} gets 60, not a day.</li>
 *   <li>The configured max is itself clamped, and the default duration is then clamped to the
 *       clamped max, so {@code default > max} cannot produce a session longer than the max.</li>
 *   <li>Zero and negative mean "unset" and resolve to the default, matching the convention
 *       {@link ConfigRules#resolveLogCooldownSeconds} already established for cooldowns.</li>
 * </ul>
 */
public record PluginScanSettings(boolean enabled,
                                 int defaultDurationSeconds,
                                 int maxDurationSeconds,
                                 int topResults,
                                 Confidence minimumConfidence,
                                 int progressIntervalSeconds) {

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_DURATION_SECONDS = 10;
    public static final int DEFAULT_MAX_DURATION_SECONDS = 30;
    public static final int DEFAULT_TOP_RESULTS = 10;
    public static final Confidence DEFAULT_MINIMUM_CONFIDENCE = Confidence.LOW;
    public static final int DEFAULT_PROGRESS_INTERVAL_SECONDS = 5;

    /**
     * The ceiling config.yml cannot raise. This is not the configured maximum - it is the
     * maximum the configured maximum is allowed to be. Profiling replaces other plugins'
     * listener registrations while it runs, so an unbounded window is a correctness risk and
     * not merely a slow command.
     */
    public static final int HARD_MAX_DURATION_SECONDS = 60;

    /** Below this a window is too short to observe anything useful across a few ticks. */
    public static final int MIN_DURATION_SECONDS = 3;

    /** Keeps a report readable and the sort bounded. */
    public static final int MAX_TOP_RESULTS = 50;

    public PluginScanSettings {
        maxDurationSeconds = clampMax(maxDurationSeconds);
        defaultDurationSeconds = clampDuration(defaultDurationSeconds, maxDurationSeconds);
        topResults = clampTopResults(topResults);
        progressIntervalSeconds = Math.max(1, progressIntervalSeconds);
        minimumConfidence = minimumConfidence == null ? DEFAULT_MINIMUM_CONFIDENCE : minimumConfidence;
    }

    public static PluginScanSettings defaults() {
        return new PluginScanSettings(DEFAULT_ENABLED, DEFAULT_DURATION_SECONDS,
                DEFAULT_MAX_DURATION_SECONDS, DEFAULT_TOP_RESULTS, DEFAULT_MINIMUM_CONFIDENCE,
                DEFAULT_PROGRESS_INTERVAL_SECONDS);
    }

    /**
     * The configured maximum, clamped to the hard ceiling. Zero or negative means unset.
     *
     * <p>There is deliberately no "unlimited" value: {@link #HARD_MAX_DURATION_SECONDS} applies
     * whatever is written, so no configuration can produce an effectively infinite session.
     */
    public static int clampMax(int configuredMax) {
        if (configuredMax <= 0) {
            return DEFAULT_MAX_DURATION_SECONDS;
        }
        return Math.min(HARD_MAX_DURATION_SECONDS, Math.max(MIN_DURATION_SECONDS, configuredMax));
    }

    /**
     * A requested or configured duration, clamped into {@code [MIN, max]}. Zero or negative
     * means unset and resolves to the default duration, itself capped by {@code max}.
     */
    public static int clampDuration(int requested, int max) {
        int ceiling = clampMax(max);
        if (requested <= 0) {
            return Math.min(DEFAULT_DURATION_SECONDS, ceiling);
        }
        return Math.min(ceiling, Math.max(MIN_DURATION_SECONDS, requested));
    }

    public static int clampTopResults(int configured) {
        if (configured <= 0) {
            return DEFAULT_TOP_RESULTS;
        }
        return Math.min(MAX_TOP_RESULTS, configured);
    }

    /**
     * Parses the configured minimum confidence. An unrecognised value resolves to the default
     * rather than throwing: a typo in config.yml must not stop the server starting.
     */
    public static Confidence parseConfidence(String raw) {
        if (raw == null) {
            return DEFAULT_MINIMUM_CONFIDENCE;
        }
        try {
            return Confidence.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT_MINIMUM_CONFIDENCE;
        }
    }

    /** The duration a session started with no explicit argument runs for. */
    public int resolveDuration(int requestedSeconds) {
        return clampDuration(requestedSeconds <= 0 ? defaultDurationSeconds : requestedSeconds,
                maxDurationSeconds);
    }
}
