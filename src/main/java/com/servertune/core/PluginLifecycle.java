package com.servertune.core;

import com.servertune.ServerTunePlugin;
import com.servertune.alert.AlertManager;
import com.servertune.blockentity.BlockEntityTracker;
import com.servertune.chunk.ChunkTracker;
import com.servertune.command.CommandManager;
import com.servertune.config.ConfigManager;
import com.servertune.diagnostics.DiagnosticsEngine;
import com.servertune.guard.PerformanceGuard;
import com.servertune.metrics.MetricsCollector;
import com.servertune.metrics.SnapshotCache;
import com.servertune.monitoring.HealthMonitor;
import com.servertune.pluginscan.PluginDiagnosticsService;
import com.servertune.scheduler.OptimizerScheduler;
import com.servertune.selfmonitor.SelfPerformanceMonitor;

import java.util.logging.Level;

public class PluginLifecycle {

    private final ServerTunePlugin plugin;
    private boolean initialized = false;

    public PluginLifecycle(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        if (initialized) {
            plugin.getLogger().warning("Plugin already initialized");
            return;
        }

        try {
            plugin.getLogger().info("Starting ServerTune initialization...");

            // Stage 1: Configuration
            plugin.getLogger().info("[1/13] Loading configuration...");
            ConfigManager configManager = new ConfigManager(plugin);
            configManager.loadConfig();
            plugin.setConfigManager(configManager);

            // Stage 2: Self-performance monitor
            plugin.getLogger().info("[2/13] Initializing self-performance monitor...");
            SelfPerformanceMonitor selfMonitor = new SelfPerformanceMonitor(plugin);
            plugin.setSelfMonitor(selfMonitor);

            // Stage 3: Scheduler
            plugin.getLogger().info("[3/13] Initializing scheduler...");
            OptimizerScheduler scheduler = new OptimizerScheduler(plugin);
            plugin.setScheduler(scheduler);

            // Stage 4: Metrics collector
            plugin.getLogger().info("[4/13] Initializing metrics collector...");
            MetricsCollector metricsCollector = new MetricsCollector(plugin);
            plugin.setMetricsCollector(metricsCollector);

            // Stage 5: Snapshot cache
            plugin.getLogger().info("[5/13] Initializing snapshot cache...");
            SnapshotCache snapshotCache = new SnapshotCache(plugin);
            plugin.setSnapshotCache(snapshotCache);

            // Stage 6: Performance guard (owns the NORMAL/WARNING/CRITICAL/FALLBACK/RECOVERING
            // state machine; must exist before the health monitor starts feeding it)
            plugin.getLogger().info("[6/13] Initializing performance guard...");
            PerformanceGuard performanceGuard = new PerformanceGuard(plugin);
            plugin.setPerformanceGuard(performanceGuard);

            // Stage 7: Alert manager
            plugin.getLogger().info("[7/13] Initializing alert manager...");
            AlertManager alertManager = new AlertManager(plugin);
            plugin.setAlertManager(alertManager);

            // Stage 8: Diagnostics engine
            plugin.getLogger().info("[8/13] Initializing diagnostics engine...");
            DiagnosticsEngine diagnosticsEngine = new DiagnosticsEngine(plugin);
            plugin.setDiagnosticsEngine(diagnosticsEngine);

            // Stage 9: Chunk tracker
            plugin.getLogger().info("[9/13] Initializing chunk tracker...");
            ChunkTracker chunkTracker = new ChunkTracker(plugin);
            plugin.setChunkTracker(chunkTracker);

            // Stage 10: Block entity tracker (after chunk tracker, feeds chunk activity)
            plugin.getLogger().info("[10/13] Initializing block entity tracker...");
            BlockEntityTracker blockEntityTracker = new BlockEntityTracker(plugin);
            plugin.setBlockEntityTracker(blockEntityTracker);

            // The on-demand plugin diagnostic is only constructed here, never started. It has no
            // start() by design: nothing runs until an administrator invokes the command, so
            // there is no stage number and no periodic work to schedule.
            plugin.setPluginDiagnostics(new PluginDiagnosticsService(plugin));

            // Stage 11: Module manager
            plugin.getLogger().info("[11/13] Initializing module manager...");
            ModuleManager moduleManager = new ModuleManager(plugin);
            plugin.setModuleManager(moduleManager);
            moduleManager.initializeModules();

            // Stage 12: Health monitor
            plugin.getLogger().info("[12/13] Initializing health monitor...");
            HealthMonitor healthMonitor = new HealthMonitor(plugin);
            plugin.setHealthMonitor(healthMonitor);

            // Stage 13: Commands
            plugin.getLogger().info("[13/13] Registering commands...");
            CommandManager commandManager = new CommandManager(plugin);
            commandManager.registerCommands();
            plugin.setCommandManager(commandManager);

            // Start scheduler
            scheduler.start();

            // Diagnostics sampling registers after the scheduler is up, because stop() clears
            // every registered task - starting it earlier would leave the sample task cancelled.
            diagnosticsEngine.start();

            initialized = true;
            plugin.getLogger().info("Initialization complete!");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed during initialization", e);
            throw new RuntimeException("Initialization failed", e);
        }
    }

    public void shutdown() {
        if (!initialized) {
            return;
        }

        try {
            plugin.getLogger().info("Shutting down ServerTune...");

            // First, before anything else: put back any listener the plugin diagnostic wrapped.
            // Those are other plugins' registrations, and leaving one wrapped after ServerTune
            // unloads would leave a dead class on a live handler list.
            if (plugin.getPluginDiagnostics() != null) {
                plugin.getPluginDiagnostics().shutdown();
            }

            // Stop scheduler first
            if (plugin.getScheduler() != null) {
                plugin.getScheduler().stop();
            }

            // Stop health monitor
            if (plugin.getHealthMonitor() != null) {
                plugin.getHealthMonitor().stop();
            }

            // Stop the guard before the modules, so no staged-recovery task can fire against
            // modules that are already being torn down
            if (plugin.getPerformanceGuard() != null) {
                plugin.getPerformanceGuard().shutdown();
            }

            // Stop module manager
            if (plugin.getModuleManager() != null) {
                plugin.getModuleManager().shutdown();
            }

            // Stop chunk tracker
            if (plugin.getChunkTracker() != null) {
                plugin.getChunkTracker().shutdown();
            }

            // Stop block entity tracker
            if (plugin.getBlockEntityTracker() != null) {
                plugin.getBlockEntityTracker().shutdown();
            }

            // Release the two event-driven collectors. They were the only components that
            // registered listeners and were never torn down, so before this their handlers stayed
            // on Bukkit's handler lists after disable.
            if (plugin.getMetricsCollector() != null) {
                plugin.getMetricsCollector().shutdown();
            }

            // Reset alert manager
            if (plugin.getAlertManager() != null) {
                plugin.getAlertManager().reset();
            }

            // Drop the diagnostic snapshot, so nothing can render a report built from a world
            // that no longer exists
            if (plugin.getDiagnosticsEngine() != null) {
                plugin.getDiagnosticsEngine().shutdown();
            }

            // Clear caches
            if (plugin.getSnapshotCache() != null) {
                plugin.getSnapshotCache().clear();
            }

            initialized = false;
            plugin.getLogger().info("Shutdown complete");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error during shutdown", e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }
}
