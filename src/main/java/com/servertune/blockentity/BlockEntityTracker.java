package com.servertune.blockentity;

import com.servertune.ServerTunePlugin;
import com.servertune.chunk.ChunkActivity;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-driven block entity tracking.
 *
 * Counts are established once per chunk when it loads, then adjusted incrementally on
 * block place/break. Nothing rescans the world on a timer.
 */
public class BlockEntityTracker implements Listener {

    private final ServerTunePlugin plugin;
    private final Map<String, Map<Long, BlockEntityStats>> statsByWorld;

    public BlockEntityTracker(ServerTunePlugin plugin) {
        this.plugin = plugin;
        this.statsByWorld = new ConcurrentHashMap<>();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        initializeLoadedChunks();
    }

    private void initializeLoadedChunks() {
        int chunks = 0;
        int hoppers = 0;

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                BlockEntityStats stats = scanChunk(chunk);
                if (stats != null) {
                    chunks++;
                    hoppers += stats.getHopperCount();
                }
            }
        }

        plugin.getLogger().info(String.format(
                "[BlockEntityTracker] Initialized: %d chunks scanned, %d hoppers found", chunks, hoppers));
    }

    /**
     * Scans a single chunk's block entities. Uses Chunk#getTileEntities, which reads the
     * chunk's existing block entity list rather than iterating blocks.
     */
    private BlockEntityStats scanChunk(Chunk chunk) {
        try {
            int hoppers = 0;
            int furnaces = 0;
            int brewing = 0;
            int other = 0;

            // useSnapshot = false avoids copying every block entity's state
            for (BlockState blockState : chunk.getTileEntities(false)) {
                switch (blockState.getType()) {
                    case HOPPER -> hoppers++;
                    case FURNACE, BLAST_FURNACE, SMOKER -> furnaces++;
                    case BREWING_STAND -> brewing++;
                    case CHEST, TRAPPED_CHEST, BARREL, DISPENSER, DROPPER, SHULKER_BOX -> other++;
                    default -> {
                        // Not performance-relevant for our purposes
                    }
                }
            }

            BlockEntityStats stats = getOrCreate(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
            stats.setCounts(hoppers, furnaces, brewing, other);

            syncChunkActivity(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), stats);

            return stats;

        } catch (Exception e) {
            plugin.getLogger().warning("[BlockEntityTracker] Failed to scan chunk "
                    + chunk.getX() + "," + chunk.getZ() + ": " + e.getMessage());
            return null;
        }
    }

    /** Feeds hopper presence back into the chunk activity model. */
    private void syncChunkActivity(String worldName, int chunkX, int chunkZ, BlockEntityStats stats) {
        if (plugin.getChunkTracker() == null) {
            return;
        }

        ChunkActivity activity = plugin.getChunkTracker().getChunkActivity(worldName, chunkX, chunkZ);
        if (activity != null) {
            activity.setHasHoppers(stats.getHopperCount() > 0);
            activity.updateEntityCounts(activity.getEntityCount(), stats.getTotalBlockEntities());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        scanChunk(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        Map<Long, BlockEntityStats> worldStats = statsByWorld.get(chunk.getWorld().getName());
        if (worldStats != null) {
            worldStats.remove(getChunkKey(chunk.getX(), chunk.getZ()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        adjustForBlock(event.getBlock(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        adjustForBlock(event.getBlock(), -1);
    }

    private void adjustForBlock(Block block, int delta) {
        Material type = block.getType();
        if (!isTracked(type)) {
            return;
        }

        Chunk chunk = block.getChunk();
        BlockEntityStats stats = getOrCreate(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());

        switch (type) {
            case HOPPER -> {
                stats.adjustHoppers(delta);
                syncChunkActivity(chunk.getWorld().getName(), chunk.getX(), chunk.getZ(), stats);
            }
            case FURNACE, BLAST_FURNACE, SMOKER -> stats.adjustFurnaces(delta);
            case BREWING_STAND -> stats.adjustBrewingStands(delta);
            default -> stats.adjustOther(delta);
        }
    }

    private boolean isTracked(Material type) {
        return switch (type) {
            case HOPPER, FURNACE, BLAST_FURNACE, SMOKER, BREWING_STAND,
                 CHEST, TRAPPED_CHEST, BARREL, DISPENSER, DROPPER, SHULKER_BOX -> true;
            default -> false;
        };
    }

    /**
     * Records an observed hopper transfer against its chunk.
     *
     * <p>Called from the hopper module's event handler. It looks the chunk up rather than creating
     * it: the chunk-load scan already created an entry for every loaded chunk, so a miss here means
     * the chunk has unloaded and its entry was removed by {@code onChunkUnload}. Creating one would
     * put back a record that nothing will ever remove again, because the unload event for it has
     * already fired - an unbounded leak fed by the hottest event in the module.
     */
    public void recordTransfer(String worldName, int chunkX, int chunkZ, int currentTick) {
        BlockEntityStats stats = getStats(worldName, chunkX, chunkZ);
        if (stats == null) {
            return;
        }
        stats.recordTransfer(currentTick);
    }

    public BlockEntityStats getOrCreate(String worldName, int chunkX, int chunkZ) {
        Map<Long, BlockEntityStats> worldStats =
                statsByWorld.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());
        return worldStats.computeIfAbsent(getChunkKey(chunkX, chunkZ),
                k -> new BlockEntityStats(worldName, chunkX, chunkZ));
    }

    public BlockEntityStats getStats(String worldName, int chunkX, int chunkZ) {
        Map<Long, BlockEntityStats> worldStats = statsByWorld.get(worldName);
        if (worldStats == null) {
            return null;
        }
        return worldStats.get(getChunkKey(chunkX, chunkZ));
    }

    public Map<Long, BlockEntityStats> getWorldStats(String worldName) {
        return statsByWorld.get(worldName);
    }

    public int getTotalHoppers() {
        int total = 0;
        for (Map<Long, BlockEntityStats> worldStats : statsByWorld.values()) {
            for (BlockEntityStats stats : worldStats.values()) {
                total += stats.getHopperCount();
            }
        }
        return total;
    }

    public int getTotalBlockEntities() {
        int total = 0;
        for (Map<Long, BlockEntityStats> worldStats : statsByWorld.values()) {
            for (BlockEntityStats stats : worldStats.values()) {
                total += stats.getTotalBlockEntities();
            }
        }
        return total;
    }

    public int getTrackedChunkCount() {
        return statsByWorld.values().stream().mapToInt(Map::size).sum();
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Releases the four event handlers and drops the per-chunk stats. The unregister is the point:
     * clearing the map alone left this instance on Bukkit's handler lists, so after a disable the
     * handlers still fired and rebuilt the map from chunk-load events.
     */
    public void shutdown() {
        HandlerList.unregisterAll(this);
        statsByWorld.clear();
    }
}
