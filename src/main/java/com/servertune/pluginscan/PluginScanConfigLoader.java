package com.servertune.pluginscan;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Turns {@code diagnostics.plugin-scan} into a {@link PluginScanSettings}.
 *
 * <p>Split from the record for the same reason as {@link com.servertune.diagnostics
 * .DiagnosticsConfigLoader}: this class needs Bukkit, the record does not, and the clamping
 * rules that decide how long a profiling session may run belong on the testable side.
 *
 * <p>Every value read here passes through the record's compact constructor, so a hand-edited
 * config.yml cannot produce an unclamped session no matter what it says.
 */
public final class PluginScanConfigLoader {

    private static final String ROOT = "diagnostics.plugin-scan";

    private PluginScanConfigLoader() {
    }

    public static PluginScanSettings load(FileConfiguration config) {
        if (config == null) {
            return PluginScanSettings.defaults();
        }

        return new PluginScanSettings(
                config.getBoolean(ROOT + ".enabled", PluginScanSettings.DEFAULT_ENABLED),
                config.getInt(ROOT + ".default-duration-seconds",
                        PluginScanSettings.DEFAULT_DURATION_SECONDS),
                config.getInt(ROOT + ".max-duration-seconds",
                        PluginScanSettings.DEFAULT_MAX_DURATION_SECONDS),
                config.getInt(ROOT + ".top-results", PluginScanSettings.DEFAULT_TOP_RESULTS),
                PluginScanSettings.parseConfidence(config.getString(ROOT + ".minimum-confidence")),
                config.getInt(ROOT + ".progress-interval-seconds",
                        PluginScanSettings.DEFAULT_PROGRESS_INTERVAL_SECONDS));
    }
}
