package com.servertune.config;

import com.servertune.ServerTunePlugin;
import com.servertune.pluginscan.PluginScanConfigLoader;
import com.servertune.pluginscan.PluginScanSettings;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.logging.Level;

public class ConfigManager {

    private final ServerTunePlugin plugin;
    private FileConfiguration config;
    private PerformanceProfile activeProfile;

    public ConfigManager(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        try {
            plugin.saveDefaultConfig();
            plugin.reloadConfig();
            config = plugin.getConfig();

            // Load performance profile
            String profileName = config.getString("performance.profile", "balanced");
            activeProfile = PerformanceProfile.fromString(profileName);

            // Validate configuration
            ConfigValidator.validate(config, plugin.getLogger());

            plugin.getLogger().info("Configuration loaded successfully");
            plugin.getLogger().info("Active profile: " + activeProfile.name().toLowerCase());

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load configuration", e);
            throw new RuntimeException("Configuration loading failed", e);
        }
    }

    public void reloadConfig() {
        plugin.reloadConfig();
        config = plugin.getConfig();

        String profileName = config.getString("performance.profile", "balanced");
        activeProfile = PerformanceProfile.fromString(profileName);

        ConfigValidator.validate(config, plugin.getLogger());
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public PerformanceProfile getActiveProfile() {
        return activeProfile;
    }

    public int getHealthInterval() {
        if (activeProfile == PerformanceProfile.CUSTOM) {
            return config.getInt("performance.intervals.health", 100);
        }
        return activeProfile.getHealthInterval();
    }

    public int getDeepAnalysisInterval() {
        if (activeProfile == PerformanceProfile.CUSTOM) {
            return config.getInt("performance.intervals.deep-analysis", 1200);
        }
        return activeProfile.getDeepAnalysisInterval();
    }

    /**
     * The on-demand plugin diagnostic's settings. Read fresh on each call rather than cached so a
     * reload takes effect without restarting anything; nothing is started as a side effect of
     * reading them.
     */
    public PluginScanSettings getPluginScanSettings() {
        return PluginScanConfigLoader.load(config);
    }

    public boolean isDebugEnabled() {
        return config.getBoolean("general.debug", false);
    }

    public boolean areAlertsEnabled() {
        return config.getBoolean("alerts.enabled", true);
    }

    public boolean isFallbackEnabled() {
        return config.getBoolean("fallback.enabled", true);
    }

    public boolean isSelfMonitoringEnabled() {
        return config.getBoolean("self-monitoring.enabled", true);
    }

    public double getTpsWarningThreshold() {
        return config.getDouble("alerts.tps.warning.threshold", 18.0);
    }

    public double getTpsCriticalThreshold() {
        return config.getDouble("alerts.tps.critical.threshold", 15.0);
    }

    public double getTpsEmergencyThreshold() {
        return config.getDouble("alerts.tps.emergency.threshold", 10.0);
    }

    public double getFallbackTriggerTps() {
        return config.getDouble("fallback.trigger.tps", 10.0);
    }

    public double getFallbackRecoveryTps() {
        return config.getDouble("fallback.recovery.tps", 18.0);
    }
}
