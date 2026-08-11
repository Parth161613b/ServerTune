package com.servertune.monitoring;

import com.servertune.ServerTunePlugin;
import com.servertune.metrics.HealthSnapshot;
import com.servertune.selfmonitor.ExecutionTimer;

import java.util.logging.Level;

public class HealthMonitor {

    private final ServerTunePlugin plugin;
    private volatile boolean running = false;

    public HealthMonitor(ServerTunePlugin plugin) {
        this.plugin = plugin;
        this.running = true;
    }

    public void update() {
        if (!running) {
            return;
        }

        ExecutionTimer timer = plugin.getSelfMonitor().startTimer("health-monitor");

        try {
            // Collect new snapshot
            HealthSnapshot snapshot = plugin.getMetricsCollector().collectSnapshot();

            // Update cache
            plugin.getSnapshotCache().update(snapshot);

            // Drive the performance state machine. This runs before alerts so a
            // state change and its notification land in the same sample.
            if (plugin.getPerformanceGuard() != null) {
                plugin.getPerformanceGuard().evaluate(snapshot);
            }

            // Check alerts
            if (plugin.getAlertManager() != null) {
                plugin.getAlertManager().checkAlerts(snapshot);
            }

            // Log debug information if enabled
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info(String.format(
                        "Health Update: TPS=%.2f MSPT=%.2fms CPU=%.1f%% Memory=%.1f/%.1fGB State=%s",
                        snapshot.getTps(),
                        snapshot.getMspt(),
                        snapshot.getCpuUsage(),
                        snapshot.getMemoryUsed() / (1024.0 * 1024.0 * 1024.0),
                        snapshot.getMemoryMax() / (1024.0 * 1024.0 * 1024.0),
                        plugin.getPerformanceGuard() != null ?
                            plugin.getPerformanceGuard().getCurrentState() : "UNKNOWN"
                ));
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error during health monitor update", e);
        } finally {
            timer.stop();
        }
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }
}
