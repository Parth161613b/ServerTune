package com.servertune.metrics.collector;

import com.servertune.ServerTunePlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.concurrent.atomic.AtomicInteger;

public class ChunkCollector implements Listener {

    private final ServerTunePlugin plugin;
    private final AtomicInteger loadedChunkCount;
    private boolean initialized = false;

    public ChunkCollector(ServerTunePlugin plugin) {
        this.plugin = plugin;
        this.loadedChunkCount = new AtomicInteger(0);
        initialize();
    }

    private void initialize() {
        if (initialized) {
            return;
        }

        // Register event listener
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Perform initial count
        Bukkit.getScheduler().runTask(plugin, this::performInitialCount);

        initialized = true;
    }

    private void performInitialCount() {
        try {
            int count = 0;
            for (World world : Bukkit.getWorlds()) {
                count += world.getLoadedChunks().length;
            }
            loadedChunkCount.set(count);
            plugin.getLogger().info("Chunk tracking initialized: " + count + " chunks loaded");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to perform initial chunk count: " + e.getMessage());
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        loadedChunkCount.incrementAndGet();
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        loadedChunkCount.decrementAndGet();
    }

    public int getLoadedChunks() {
        return Math.max(0, loadedChunkCount.get());
    }

    /**
     * Unregisters this listener and resets the count. Called from the one existing shutdown path
     * ({@code PluginLifecycle.shutdown} via {@code MetricsCollector.shutdown}). The count resets
     * because {@code performInitialCount} recounts from the live worlds on the next enable, so a
     * retained value would be double-counted.
     */
    public void shutdown() {
        HandlerList.unregisterAll(this);
        loadedChunkCount.set(0);
        initialized = false;
    }
}
