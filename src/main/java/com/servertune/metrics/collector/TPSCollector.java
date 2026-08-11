package com.servertune.metrics.collector;

import com.servertune.ServerTunePlugin;
import org.bukkit.Bukkit;

public class TPSCollector {

    private final ServerTunePlugin plugin;

    public TPSCollector(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    public double getTPS() {
        try {
            // Paper API: Server.getTPS() returns double[] with 1m, 5m, 15m averages
            double[] tps = Bukkit.getTPS();
            // Use 1-minute average (index 0)
            return Math.min(tps[0], 20.0);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get TPS: " + e.getMessage());
            return 20.0;
        }
    }
}
