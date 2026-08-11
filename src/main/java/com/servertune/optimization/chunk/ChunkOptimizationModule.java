package com.servertune.optimization.chunk;

import com.servertune.ServerTunePlugin;
import com.servertune.chunk.ChunkActivity;
import com.servertune.core.ModuleState;
import com.servertune.core.OptimizationModule;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Chunk optimization module with conservative inactive chunk unloading.
 * Protects player-active chunks, spawn chunks, and important plugin activity.
 */
public class ChunkOptimizationModule implements OptimizationModule {

    private final ServerTunePlugin plugin;
    private ModuleState state = ModuleState.DISABLED;

    // Configuration
    private boolean inactiveUnloadingEnabled;
    private long inactiveThresholdMillis;
    private int maxUnloadsPerCycle;
    private boolean protectSpawnChunks;
    private int spawnChunkRadius;

    // Cached config lists. optimize() consults these once per chunk, and getStringList
    // re-parses and copies on every call, so they are read at load time instead.
    private final Set<String> protectedChunks = new HashSet<>();
    private final Set<String> disabledWorlds = new HashSet<>();

    public ChunkOptimizationModule(ServerTunePlugin plugin) {
        this.plugin = plugin;
        loadConfiguration();
    }

    private void loadConfiguration() {
        String base = "optimization.modules.chunk.";
        var config = plugin.getConfig();

        inactiveUnloadingEnabled = config.getBoolean(base + "inactive-unloading.enabled", true);
        int inactiveTimeoutSeconds = config.getInt(base + "inactive-unloading.timeout-seconds", 300);
        inactiveThresholdMillis = inactiveTimeoutSeconds * 1000L;
        maxUnloadsPerCycle = config.getInt(base + "inactive-unloading.max-per-cycle", 10);
        protectSpawnChunks = config.getBoolean(base + "protect-spawn-chunks", true);
        spawnChunkRadius = config.getInt(base + "spawn-chunk-radius", 10);

        protectedChunks.clear();
        protectedChunks.addAll(config.getStringList(base + "protected-chunks"));

        disabledWorlds.clear();
        disabledWorlds.addAll(config.getStringList(base + "disabled-worlds"));
    }

    @Override
    public String getName() {
        return "chunk-optimization";
    }

    @Override
    public ModuleState getState() {
        return state;
    }

    @Override
    public void enable() {
        loadConfiguration();
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[ChunkOptimization] Enabled - inactive unloading: " + inactiveUnloadingEnabled);
    }

    @Override
    public void disable() {
        state = ModuleState.DISABLED;
        plugin.getLogger().info("[ChunkOptimization] Disabled");
    }

    @Override
    public void suspend() {
        state = ModuleState.SUSPENDED;
        plugin.getLogger().info("[ChunkOptimization] Suspended");
    }

    @Override
    public void resume() {
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[ChunkOptimization] Resumed");
    }

    @Override
    public void optimize() {
        if (state != ModuleState.ENABLED) {
            return;
        }

        if (!inactiveUnloadingEnabled) {
            return;
        }

        try {
            int unloadedCount = 0;

            // Process each world
            for (World world : Bukkit.getWorlds()) {
                if (!isWorldEnabled(world)) {
                    continue;
                }

                List<Chunk> chunksToUnload = findInactiveChunks(world);

                // Limit unloads per cycle to avoid tick spikes
                int toUnload = Math.min(chunksToUnload.size(), maxUnloadsPerCycle);

                for (int i = 0; i < toUnload; i++) {
                    Chunk chunk = chunksToUnload.get(i);
                    if (unloadChunkSafely(chunk)) {
                        unloadedCount++;
                    }
                }
            }

            if (plugin.getConfigManager().isDebugEnabled() && unloadedCount > 0) {
                plugin.getLogger().info(String.format(
                    "[ChunkOptimization] Unloaded %d inactive chunks",
                    unloadedCount
                ));
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[ChunkOptimization] Error during optimization", e);
        }
    }

    private List<Chunk> findInactiveChunks(World world) {
        List<Chunk> inactiveChunks = new ArrayList<>();

        Map<Long, ChunkActivity> worldChunks = plugin.getChunkTracker().getWorldChunks(world.getName());
        if (worldChunks == null) {
            return inactiveChunks;
        }

        // Resolved once per world instead of once per candidate chunk: getSpawnLocation()
        // allocates, and the protected-chunks list re-parses on every getStringList call.
        Location spawn = world.getSpawnLocation();
        int spawnChunkX = spawn.getBlockX() >> 4;
        int spawnChunkZ = spawn.getBlockZ() >> 4;

        for (Map.Entry<Long, ChunkActivity> entry : worldChunks.entrySet()) {
            ChunkActivity activity = entry.getValue();

            // Check if chunk is inactive
            if (!activity.isInactive(inactiveThresholdMillis)) {
                continue;
            }

            // Skip if protected
            if (isChunkProtected(world, activity, spawnChunkX, spawnChunkZ)) {
                continue;
            }

            // Only chunks already resident. world.getChunkAt(...) would LOAD the chunk we are
            // about to unload - and generate it if it does not exist yet.
            if (!world.isChunkLoaded(activity.getChunkX(), activity.getChunkZ())) {
                continue;
            }

            inactiveChunks.add(world.getChunkAt(activity.getChunkX(), activity.getChunkZ()));

            // Nothing beyond one cycle's budget can be unloaded, so stop collecting there.
            if (inactiveChunks.size() >= maxUnloadsPerCycle) {
                break;
            }
        }

        return inactiveChunks;
    }

    private boolean isChunkProtected(World world, ChunkActivity activity,
                                     int spawnChunkX, int spawnChunkZ) {
        // Protect chunks with players
        if (activity.hasPlayers()) {
            return true;
        }

        // Protect marked chunks
        if (activity.isProtected()) {
            return true;
        }

        // Protect forced loaded chunks
        if (activity.isForcedLoaded()) {
            return true;
        }

        // Protect spawn chunks if configured
        if (protectSpawnChunks
                && Math.abs(activity.getChunkX() - spawnChunkX) <= spawnChunkRadius
                && Math.abs(activity.getChunkZ() - spawnChunkZ) <= spawnChunkRadius) {
            return true;
        }

        // Protect chunks from the configured list
        if (protectedChunks.isEmpty()) {
            return false;
        }

        String chunkKey = world.getName() + ":" + activity.getChunkX() + ":" + activity.getChunkZ();
        return protectedChunks.contains(chunkKey);
    }

    private boolean unloadChunkSafely(Chunk chunk) {
        try {
            // Double-check chunk is still safe to unload
            ChunkActivity activity = plugin.getChunkTracker().getChunkActivity(chunk);
            if (activity != null && activity.hasPlayers()) {
                return false;
            }

            // Unload chunk
            return chunk.unload(true); // save = true

        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to unload chunk: " + chunk.getX() + "," + chunk.getZ(), e);
            return false;
        }
    }

    private boolean isWorldEnabled(World world) {
        return !disabledWorlds.contains(world.getName());
    }
}
