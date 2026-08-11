package com.servertune.metrics.collector;

import com.servertune.ServerTunePlugin;
import org.bukkit.Bukkit;

public class PlayerCollector {

    private final ServerTunePlugin plugin;

    public PlayerCollector(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    public int getOnlinePlayerCount() {
        try {
            return Bukkit.getOnlinePlayers().size();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get online player count: " + e.getMessage());
            return 0;
        }
    }

    public int getMaxPlayers() {
        try {
            return Bukkit.getMaxPlayers();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get max players: " + e.getMessage());
            return 0;
        }
    }
}
