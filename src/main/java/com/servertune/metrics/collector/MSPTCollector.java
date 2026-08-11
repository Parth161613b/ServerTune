package com.servertune.metrics.collector;

import com.servertune.ServerTunePlugin;
import org.bukkit.Bukkit;

public class MSPTCollector {

    private final ServerTunePlugin plugin;

    public MSPTCollector(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    public double getMSPT() {
        try {
            // Paper API: Server.getAverageTickTime() returns average tick time in milliseconds
            return Bukkit.getAverageTickTime();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get MSPT: " + e.getMessage());
            return 0.0;
        }
    }
}
