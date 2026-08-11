package com.servertune.config;

/**
 * The decisions behind configuration validation, with no Bukkit types anywhere.
 *
 * <p>An operator editing config.yml by hand will eventually type something impossible: a
 * negative interval, a TPS threshold of 25 on a 20 TPS server, a profile name that does not
 * exist. Every rule here answers one question - is this value usable, and if not, what is used
 * instead - and answers it the same way whether it is called from a live server or from a test.
 *
 * <p>{@link ConfigValidator} holds the list of paths and the Bukkit read/write; this class holds
 * the judgement. The split exists because paper-api is {@code compileOnly}, so anything that
 * touches {@code FileConfiguration} cannot be reached from a unit test.
 *
 * <p>Every rule corrects rather than rejects. A bad value must never stop the plugin from
 * loading: a server that will not start because one number was mistyped is worse for the owner
 * than a server that logs the correction and runs on the default.
 */
public final class ConfigRules {

    /** A TPS threshold above this is unreachable - the server ceiling is 20 TPS. */
    public static final double MAX_TPS = 20.0;

    /** TPS cannot be negative, and 0 means "completely frozen" rather than "unset". */
    public static final double MIN_TPS = 0.0;

    /**
     * Floor for a console log cooldown, in seconds.
     *
     * <p>Console rate limits are not like the other durations here: there is no legitimate reason
     * to want an identical line every monitoring cycle, and a subsystem that is permanently over
     * budget would produce one every 5 seconds at the default health interval. A cooldown small
     * enough to allow that turns a diagnostic aid into the log-flooding problem it is meant to
     * describe, so values below this floor are raised to it rather than honoured.
     */
    public static final int MIN_LOG_COOLDOWN_SECONDS = 10;

    private ConfigRules() {
    }

    /**
     * The four profile names {@link PerformanceProfile} defines. Compared case-insensitively
     * because config.yml is hand-edited and {@code Balanced} is not a mistake worth punishing.
     */
    public static boolean isValidProfile(String profile) {
        if (profile == null) {
            return false;
        }
        for (PerformanceProfile candidate : PerformanceProfile.values()) {
            if (candidate.name().equalsIgnoreCase(profile.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Corrects an unrecognised profile name to {@code fallback}. */
    public static ConfigValue profile(String path, String value, String fallback) {
        if (isValidProfile(value)) {
            return valid(path, value);
        }
        return corrected(path, value, fallback,
                "Invalid value for " + path + ": '" + value
                        + "' is not a known profile, using default: " + fallback);
    }

    /**
     * A tick interval or a duration in seconds. Zero and negative are both rejected: the
     * scheduler treats a period of zero as "every tick", which is the opposite of what a
     * performance plugin should do when asked to run less often.
     */
    public static ConfigValue positive(String path, int value, int defaultValue) {
        if (value > 0) {
            return valid(path, value);
        }
        return corrected(path, value, defaultValue, outOfRangeMessage(path, value, defaultValue));
    }

    /** The double form, for MSPT thresholds and other non-tick quantities. */
    public static ConfigValue positive(String path, double value, double defaultValue) {
        if (value > 0.0) {
            return valid(path, value);
        }
        return corrected(path, value, defaultValue, outOfRangeMessage(path, value, defaultValue));
    }

    /**
     * A value where zero is a legitimate setting but a negative one is not. Hysteresis margins and
     * the anti-flap timer are the cases: 0 means "no margin", which is a real choice, while a
     * negative margin would invert the comparison it feeds and make de-escalation easier the
     * further the metric sat the wrong side of the line.
     */
    public static ConfigValue nonNegative(String path, double value, double defaultValue) {
        if (value >= 0.0) {
            return valid(path, value);
        }
        return corrected(path, value, defaultValue, outOfRangeMessage(path, value, defaultValue));
    }

    /** The int form, for second counts that may legitimately be zero. */
    public static ConfigValue nonNegative(String path, int value, int defaultValue) {
        if (value >= 0) {
            return valid(path, value);
        }
        return corrected(path, value, defaultValue, outOfRangeMessage(path, value, defaultValue));
    }

    /**
     * A value that has to sit inside a closed interval. Used for TPS thresholds, where anything
     * above {@link #MAX_TPS} can never trigger and anything below {@link #MIN_TPS} is nonsense.
     */
    public static ConfigValue inRange(String path, double value, double defaultValue,
                                      double min, double max) {
        if (value >= min && value <= max) {
            return valid(path, value);
        }
        return corrected(path, value, defaultValue,
                "Value for " + path + " (" + value + ") outside valid range ["
                        + min + ", " + max + "], using default: " + defaultValue);
    }

    /** Shorthand for the TPS ladder, every entry of which shares the same bounds. */
    public static ConfigValue tpsThreshold(String path, double value, double defaultValue) {
        return inRange(path, value, defaultValue, MIN_TPS, MAX_TPS);
    }

    /**
     * Resolves a configured console-log cooldown to the value actually used.
     *
     * <p>Two documented behaviours, and both are corrections rather than rejections:
     * <ul>
     *   <li>{@code 0} - and any negative - means "unset", and resolves to {@code defaultSeconds}.
     *       It does <em>not</em> mean "log every cycle"; there is no setting for that.</li>
     *   <li>Anything between 1 and {@link #MIN_LOG_COOLDOWN_SECONDS} is raised to the floor.
     *       A one-second cooldown on a five-second sample interval is not meaningfully different
     *       from no cooldown at all.</li>
     * </ul>
     *
     * <p>Above the floor the operator's value is used unchanged, however large.
     */
    public static int resolveLogCooldownSeconds(int value, int defaultSeconds) {
        int effectiveDefault = Math.max(MIN_LOG_COOLDOWN_SECONDS, defaultSeconds);
        if (value <= 0) {
            return effectiveDefault;
        }
        return Math.max(MIN_LOG_COOLDOWN_SECONDS, value);
    }

    /**
     * The {@link ConfigValue} form of {@link #resolveLogCooldownSeconds}, so the validator can log
     * what it changed. A value already at or above the floor passes through untouched and silently.
     */
    public static ConfigValue logCooldownSeconds(String path, int value, int defaultSeconds) {
        int resolved = resolveLogCooldownSeconds(value, defaultSeconds);
        if (resolved == value) {
            return valid(path, value);
        }
        if (value <= 0) {
            return corrected(path, value, resolved,
                    "Value for " + path + " (" + value + ") means 'unset' rather than 'log every "
                            + "cycle', using default: " + resolved + "s");
        }
        return corrected(path, value, resolved,
                "Value for " + path + " (" + value + "s) is below the " + MIN_LOG_COOLDOWN_SECONDS
                        + "s minimum for console log cooldowns, using: " + resolved + "s");
    }

    private static String outOfRangeMessage(String path, Object value, Object defaultValue) {
        return "Invalid value for " + path + ": " + value + ", using default: " + defaultValue;
    }

    private static ConfigValue valid(String path, Object value) {
        return new ConfigValue(path, value, null, null);
    }

    private static ConfigValue corrected(String path, Object value, Object replacement,
                                         String message) {
        return new ConfigValue(path, value, replacement, message);
    }
}
