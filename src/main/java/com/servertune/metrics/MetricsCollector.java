package com.servertune.metrics;

import com.servertune.ServerTunePlugin;
import com.servertune.metrics.collector.*;
import com.servertune.selfmonitor.ExecutionTimer;
import org.bukkit.entity.EntityType;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class MetricsCollector {

    private final ServerTunePlugin plugin;
    private final TPSCollector tpsCollector;
    private final MSPTCollector msptCollector;
    private final CPUCollector cpuCollector;
    private final MemoryCollector memoryCollector;
    private final EntityCollector entityCollector;
    private final ChunkCollector chunkCollector;
    private final PlayerCollector playerCollector;
    private final PluginCollector pluginCollector;
    private final WorldCollector worldCollector;

    public MetricsCollector(ServerTunePlugin plugin) {
        this.plugin = plugin;
        this.tpsCollector = new TPSCollector(plugin);
        this.msptCollector = new MSPTCollector(plugin);
        this.cpuCollector = new CPUCollector(plugin);
        this.memoryCollector = new MemoryCollector(plugin);
        this.entityCollector = new EntityCollector(plugin);
        this.chunkCollector = new ChunkCollector(plugin);
        this.playerCollector = new PlayerCollector(plugin);
        this.pluginCollector = new PluginCollector(plugin);
        this.worldCollector = new WorldCollector(plugin);
    }

    public HealthSnapshot collectSnapshot() {
        ExecutionTimer timer = plugin.getSelfMonitor().startTimer("metrics-collection");

        try {
            double tps = tpsCollector.getTPS();
            double mspt = msptCollector.getMSPT();
            double cpu = cpuCollector.getCPU();

            MemoryCollector.MemoryStats memory = memoryCollector.getMemoryStats();

            int onlinePlayers = playerCollector.getOnlinePlayerCount();
            int maxPlayers = playerCollector.getMaxPlayers();

            int loadedChunks = chunkCollector.getLoadedChunks();

            Map<EntityType, Integer> entityCounts = entityCollector.getEntityCounts();
            int totalEntities = entityCounts.values().stream().mapToInt(Integer::intValue).sum();

            int loadedWorlds = worldCollector.getLoadedWorldCount();
            int loadedPlugins = pluginCollector.getLoadedPluginCount();
            int loadedDatapacks = worldCollector.getLoadedDatapackCount();

            String status = determineServerStatus(tps);
            String profile = plugin.getConfigManager().getActiveProfile().name().toLowerCase();

            return HealthSnapshot.builder()
                    .timestamp(System.currentTimeMillis())
                    .tps(tps)
                    .mspt(mspt)
                    .cpuUsage(cpu)
                    .memoryUsed(memory.used)
                    .memoryAllocated(memory.allocated)
                    .memoryMax(memory.max)
                    .onlinePlayers(onlinePlayers)
                    .maxPlayers(maxPlayers)
                    .loadedChunks(loadedChunks)
                    .totalEntities(totalEntities)
                    .entityCounts(entityCounts)
                    .loadedWorlds(loadedWorlds)
                    .loadedPlugins(loadedPlugins)
                    .loadedDatapacks(loadedDatapacks)
                    .serverStatus(status)
                    .activeProfile(profile)
                    .build();

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error collecting metrics snapshot", e);
            return createEmergencySnapshot();
        } finally {
            timer.stop();
        }
    }

    private String determineServerStatus(double tps) {
        if (tps >= 19.5) {
            return "HEALTHY";
        } else if (tps >= 18.0) {
            return "GOOD";
        } else if (tps >= 15.0) {
            return "WARNING";
        } else if (tps >= 10.0) {
            return "CRITICAL";
        } else {
            return "EMERGENCY";
        }
    }

    private HealthSnapshot createEmergencySnapshot() {
        return HealthSnapshot.builder()
                .timestamp(System.currentTimeMillis())
                .tps(0.0)
                .mspt(0.0)
                .cpuUsage(0.0)
                .memoryUsed(0)
                .memoryAllocated(0)
                .memoryMax(0)
                .onlinePlayers(0)
                .maxPlayers(0)
                .loadedChunks(0)
                .totalEntities(0)
                .entityCounts(new HashMap<>())
                .loadedWorlds(0)
                .loadedPlugins(0)
                .loadedDatapacks(0)
                .serverStatus("ERROR")
                .activeProfile("unknown")
                .build();
    }

    public EntityCollector getEntityCollector() {
        return entityCollector;
    }

    public ChunkCollector getChunkCollector() {
        return chunkCollector;
    }

    /**
     * Shuts down the two collectors that register Bukkit listeners. The other seven poll on
     * demand and hold no registration, so there is nothing to release for them.
     */
    public void shutdown() {
        entityCollector.shutdown();
        chunkCollector.shutdown();
    }
}
