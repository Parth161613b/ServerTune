package com.servertune;

import com.servertune.alert.AlertManager;
import com.servertune.blockentity.BlockEntityTracker;
import com.servertune.chunk.ChunkTracker;
import com.servertune.command.CommandManager;
import com.servertune.config.ConfigManager;
import com.servertune.core.ModuleManager;
import com.servertune.core.PluginLifecycle;
import com.servertune.diagnostics.DiagnosticsEngine;
import com.servertune.guard.PerformanceGuard;
import com.servertune.metrics.MetricsCollector;
import com.servertune.metrics.SnapshotCache;
import com.servertune.monitoring.HealthMonitor;
import com.servertune.pluginscan.PluginDiagnosticsService;
import com.servertune.scheduler.OptimizerScheduler;
import com.servertune.selfmonitor.SelfPerformanceMonitor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class ServerTunePlugin extends JavaPlugin {

    private static ServerTunePlugin instance;

    // Core components
    private PluginLifecycle lifecycle;
    private ConfigManager configManager;
    private ModuleManager moduleManager;
    private OptimizerScheduler scheduler;
    private MetricsCollector metricsCollector;
    private SnapshotCache snapshotCache;
    private HealthMonitor healthMonitor;
    private SelfPerformanceMonitor selfMonitor;
    private CommandManager commandManager;
    private PerformanceGuard performanceGuard;
    private AlertManager alertManager;
    private DiagnosticsEngine diagnosticsEngine;
    private ChunkTracker chunkTracker;
    private BlockEntityTracker blockEntityTracker;
    private PluginDiagnosticsService pluginDiagnostics;

    @Override
    public void onEnable() {
        instance = this;

        try {
            // Initialize lifecycle manager
            lifecycle = new PluginLifecycle(this);

            // Start initialization sequence
            lifecycle.initialize();

            getLogger().info("ServerTune v" + getPluginMeta().getVersion() + " enabled successfully");
            getLogger().info("Performance profile: " + configManager.getActiveProfile());

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize ServerTune", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (lifecycle != null) {
                lifecycle.shutdown();
            }

            getLogger().info("ServerTune disabled successfully");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error during plugin shutdown", e);
        } finally {
            instance = null;
        }
    }

    // Getters for components
    public static ServerTunePlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public void setConfigManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public void setModuleManager(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    public OptimizerScheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(OptimizerScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    public void setMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    public SnapshotCache getSnapshotCache() {
        return snapshotCache;
    }

    public void setSnapshotCache(SnapshotCache snapshotCache) {
        this.snapshotCache = snapshotCache;
    }

    public HealthMonitor getHealthMonitor() {
        return healthMonitor;
    }

    public void setHealthMonitor(HealthMonitor healthMonitor) {
        this.healthMonitor = healthMonitor;
    }

    public SelfPerformanceMonitor getSelfMonitor() {
        return selfMonitor;
    }

    public void setSelfMonitor(SelfPerformanceMonitor selfMonitor) {
        this.selfMonitor = selfMonitor;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public void setCommandManager(CommandManager commandManager) {
        this.commandManager = commandManager;
    }

    public PerformanceGuard getPerformanceGuard() {
        return performanceGuard;
    }

    public void setPerformanceGuard(PerformanceGuard performanceGuard) {
        this.performanceGuard = performanceGuard;
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public void setAlertManager(AlertManager alertManager) {
        this.alertManager = alertManager;
    }

    public DiagnosticsEngine getDiagnosticsEngine() {
        return diagnosticsEngine;
    }

    public void setDiagnosticsEngine(DiagnosticsEngine diagnosticsEngine) {
        this.diagnosticsEngine = diagnosticsEngine;
    }

    public ChunkTracker getChunkTracker() {
        return chunkTracker;
    }

    public void setChunkTracker(ChunkTracker chunkTracker) {
        this.chunkTracker = chunkTracker;
    }

    public BlockEntityTracker getBlockEntityTracker() {
        return blockEntityTracker;
    }

    public void setBlockEntityTracker(BlockEntityTracker blockEntityTracker) {
        this.blockEntityTracker = blockEntityTracker;
    }

    /**
     * The on-demand plugin diagnostic. Holding the reference costs nothing: the service starts
     * no task and wraps no listener until an administrator runs the command.
     */
    public PluginDiagnosticsService getPluginDiagnostics() {
        return pluginDiagnostics;
    }

    public void setPluginDiagnostics(PluginDiagnosticsService pluginDiagnostics) {
        this.pluginDiagnostics = pluginDiagnostics;
    }
}
