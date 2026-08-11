package com.servertune.metrics.collector;

import com.servertune.ServerTunePlugin;
import org.bukkit.Bukkit;

public class WorldCollector {

    private final ServerTunePlugin plugin;

    public WorldCollector(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    public int getLoadedWorldCount() {
        try {
            return Bukkit.getWorlds().size();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get world count: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Enabled datapacks, which are a server-wide set rather than a per-world one. Reads the
     * loaded pack list only; {@code refreshPacks()} would rescan the datapack directory from
     * disk on the main thread, which is not something a 5-second health sample should do.
     */
    public int getLoadedDatapackCount() {
        try {
            return Bukkit.getDatapackManager().getEnabledPacks().size();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get datapack count: " + e.getMessage());
            return 0;
        }
    }
}
