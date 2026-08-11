package com.servertune.metrics.collector;

import com.servertune.ServerTunePlugin;
import org.bukkit.Bukkit;

public class PluginCollector {

    private final ServerTunePlugin plugin;

    public PluginCollector(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    public int getLoadedPluginCount() {
        try {
            return Bukkit.getPluginManager().getPlugins().length;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get plugin count: " + e.getMessage());
            return 0;
        }
    }
}
