package com.servertune.diagnostics;

import com.servertune.ServerTunePlugin;
import com.servertune.blockentity.BlockEntityStats;
import com.servertune.chunk.ChunkActivity;
import com.servertune.core.ModuleState;
import com.servertune.core.OptimizationModule;
import com.servertune.metrics.HealthSnapshot;
import com.servertune.optimization.hopper.HopperOptimizationModule;
import com.servertune.optimization.redstone.RedstoneOptimizationModule;
import com.servertune.redstone.RedstoneActivity;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link DiagnosticSnapshot} on the main thread.
 *
 * <p>This is the only Bukkit-facing half of the diagnostics system. Everything it produces
 * is immutable and free of Bukkit types, so the analyzer and recommendation engine can then
 * run on an async thread without touching an unsafe API.
 *
 * <p>Per-chunk entity counts come from a bounded pass over loaded chunks. {@code Chunk#getEntities()}
 * is main-thread-only and is the only way to attribute entities to a chunk, so the pass runs
 * here, visits at most {@code chunkScanBudget} chunks per cycle, and resumes where it stopped
 * next cycle. Without it, hotspot entity counts would be unmeasured - and reporting a count
 * the plugin never took would be worse than reporting none.
 */
public final class DiagnosticSampler {

    private final ServerTunePlugin plugin;
    private final HotspotRanker ranker;
    private final int chunkScanBudget;

    /** Where the last bounded pass stopped, so successive cycles cover everything. */
    private int scanCursor = 0;

    public DiagnosticSampler(ServerTunePlugin plugin, HotspotRanker ranker,
                             int chunkScanBudget) {
        this.plugin = plugin;
        this.ranker = ranker;
        this.chunkScanBudget = Math.max(1, chunkScanBudget);
    }

    /** Must be called on the main thread. */
    public DiagnosticSnapshot sample(HealthSnapshot health) {
        long now = System.currentTimeMillis();

        Map<String, Integer> entityCounts = new HashMap<>();
        int items = 0;
        int xpOrbs = 0;
        int mobs = 0;
        int villagers = 0;

        if (health != null) {
            for (Map.Entry<EntityType, Integer> e : health.getEntityCounts().entrySet()) {
                EntityType type = e.getKey();
                int count = e.getValue();
                entityCounts.put(type.name(), count);

                if (type == EntityType.ITEM) {
                    items += count;
                } else if (type == EntityType.EXPERIENCE_ORB) {
                    xpOrbs += count;
                } else if (isVillager(type)) {
                    villagers += count;
                } else if (isMob(type)) {
                    mobs += count;
                }
            }
        }

        ChunkScan scan = scanChunks();

        DiagnosticSnapshot.Builder b = DiagnosticSnapshot.builder()
                .sampledAt(now)
                .entityCounts(entityCounts)
                .itemCount(items)
                .xpOrbCount(xpOrbs)
                .mobCount(mobs)
                .villagerCount(villagers)
                .hotspots(scan.hotspots())
                .inactiveChunks(scan.inactiveChunks())
                .forceLoadedChunks(scan.forceLoadedChunks())
                .perChunkEntityCountsMeasured(scan.entityCountsMeasured())
                .enabledModules(enabledModules())
                .itemMergingEnabled(plugin.getConfig()
                        .getBoolean("optimization.modules.item.merging.enabled", false))
                .inactiveChunkUnloadingEnabled(plugin.getConfig()
                        .getBoolean("optimization.modules.chunk.inactive-unloading.enabled", false));

        if (health != null) {
            b.tps(health.getTps())
                    .mspt(health.getMspt())
                    .cpuUsage(health.getCpuUsage())
                    .memoryUsed(health.getMemoryUsed())
                    .memoryAllocated(health.getMemoryAllocated())
                    .memoryMax(health.getMemoryMax())
                    .onlinePlayers(health.getOnlinePlayers())
                    .maxPlayers(health.getMaxPlayers())
                    .loadedChunks(health.getLoadedChunks())
                    .totalEntities(health.getTotalEntities())
                    .serverStatus(health.getServerStatus())
                    .activeProfile(health.getActiveProfile());
        }

        if (plugin.getChunkTracker() != null) {
            b.chunkLoadRate(plugin.getChunkTracker().getChunkLoadRate())
                    .chunkUnloadRate(plugin.getChunkTracker().getChunkUnloadRate())
                    .trackedChunks(plugin.getChunkTracker().getTotalLoadedChunks());
        }

        if (plugin.getBlockEntityTracker() != null) {
            b.hopperCount(plugin.getBlockEntityTracker().getTotalHoppers())
                    .blockEntityCount(plugin.getBlockEntityTracker().getTotalBlockEntities());
        }

        b.hopperTransfersPerWindow(scan.hopperTransfers())
                .redstoneActivityPerWindow(scan.redstoneActivity());

        if (plugin.getPerformanceGuard() != null) {
            b.guardState(plugin.getPerformanceGuard().getCurrentState().name());
        }

        return b.build();
    }

    /** Result of one bounded pass over loaded chunks. */
    private record ChunkScan(List<ChunkHotspot> hotspots,
                             int inactiveChunks,
                             int forceLoadedChunks,
                             long hopperTransfers,
                             long redstoneActivity,
                             boolean entityCountsMeasured) {
    }

    private ChunkScan scanChunks() {
        List<HotspotRanker.Candidate> candidates = new ArrayList<>();
        int inactive = 0;
        int forceLoaded = 0;
        long hopperTransfers = 0L;
        long redstoneActivity = 0L;
        boolean measuredAny = false;

        RedstoneOptimizationModule redstone = redstoneModule();
        long inactiveThreshold = plugin.getConfig()
                .getLong("optimization.modules.chunk.inactive-unloading.timeout-seconds", 300) * 1000L;

        // The per-world arrays the API already allocated are indexed in place. Copying them
        // into one flat list would hold a second reference to every loaded chunk on the
        // server - thousands of entries - to then visit at most chunkScanBudget of them.
        List<World> worlds = Bukkit.getWorlds();
        Chunk[][] loadedByWorld = new Chunk[worlds.size()][];
        int total = 0;
        for (int w = 0; w < worlds.size(); w++) {
            loadedByWorld[w] = worlds.get(w).getLoadedChunks();
            total += loadedByWorld[w].length;
        }

        if (total == 0) {
            scanCursor = 0;
            return new ChunkScan(List.of(), 0, 0, 0L, 0L, false);
        }

        if (scanCursor >= total) {
            scanCursor = 0;
        }

        int visit = Math.min(chunkScanBudget, total);
        for (int i = 0; i < visit; i++) {
            Chunk chunk = chunkAt(loadedByWorld, (scanCursor + i) % total);

            int entityCount = ChunkHotspot.NOT_MEASURED;
            try {
                entityCount = chunk.getEntities().length;
                measuredAny = true;
            } catch (Exception e) {
                // A chunk can go away between listing and reading; treat as unmeasured.
            }

            int hoppers = 0;
            int blockEntities = ChunkHotspot.NOT_MEASURED;
            long transfers = 0L;
            if (plugin.getBlockEntityTracker() != null) {
                BlockEntityStats stats = plugin.getBlockEntityTracker()
                        .getStats(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
                if (stats != null) {
                    hoppers = stats.getHopperCount();
                    blockEntities = stats.getTotalBlockEntities();
                    // Read without resetting: the hopper module owns this window.
                    transfers = stats.getTransfersThisWindow();
                    hopperTransfers += transfers;
                }
            }

            long redstoneHere = 0L;
            if (redstone != null) {
                RedstoneActivity activity = redstone.getActivity(chunk);
                if (activity != null) {
                    redstoneHere = activity.getLifetimeTotal();
                    redstoneActivity += redstoneHere;
                }
            }

            int players = 0;
            long inactiveSeconds = 0L;
            if (plugin.getChunkTracker() != null) {
                ChunkActivity activity = plugin.getChunkTracker().getChunkActivity(chunk);
                if (activity != null) {
                    // Feed the measured count back into the chunk activity model; the sampler
                    // is the only thing that populates it.
                    if (entityCount != ChunkHotspot.NOT_MEASURED) {
                        activity.updateEntityCounts(entityCount, activity.getTileEntityCount());
                    }

                    players = activity.getPlayerCount();
                    inactiveSeconds = activity.getInactiveSeconds();

                    boolean forced = chunk.isForceLoaded();
                    activity.setForcedLoaded(forced);
                    if (forced) {
                        forceLoaded++;
                    }

                    if (!activity.hasPlayers() && activity.isInactive(inactiveThreshold)) {
                        inactive++;
                    }
                } else if (chunk.isForceLoaded()) {
                    forceLoaded++;
                }
            } else if (chunk.isForceLoaded()) {
                forceLoaded++;
            }

            candidates.add(new HotspotRanker.Candidate(
                    chunk.getWorld().getName(), chunk.getX(), chunk.getZ(),
                    entityCount, hoppers, blockEntities, transfers, redstoneHere,
                    players, inactiveSeconds));
        }

        scanCursor = (scanCursor + visit) % total;

        return new ChunkScan(ranker.rank(candidates), inactive, forceLoaded,
                hopperTransfers, redstoneActivity, measuredAny);
    }

    /**
     * The chunk at a flat index across every world's loaded-chunk array, without flattening
     * them. Worlds are few, so the walk over the outer array is negligible next to the copy
     * it replaces.
     */
    private static Chunk chunkAt(Chunk[][] loadedByWorld, int index) {
        for (Chunk[] worldChunks : loadedByWorld) {
            if (index < worldChunks.length) {
                return worldChunks[index];
            }
            index -= worldChunks.length;
        }
        // Unreachable: index is always taken modulo the same total these lengths sum to.
        throw new IndexOutOfBoundsException("chunk index past the end of every world");
    }

    private Set<String> enabledModules() {
        Set<String> enabled = new HashSet<>();
        if (plugin.getModuleManager() == null) {
            return enabled;
        }

        for (Map.Entry<String, OptimizationModule> e
                : plugin.getModuleManager().getAllModules().entrySet()) {
            if (e.getValue().getState() == ModuleState.ENABLED) {
                enabled.add(e.getKey());
            }
        }
        return enabled;
    }

    private RedstoneOptimizationModule redstoneModule() {
        if (plugin.getModuleManager() == null) {
            return null;
        }
        OptimizationModule module = plugin.getModuleManager().getModule("redstone-optimization");
        return module instanceof RedstoneOptimizationModule r ? r : null;
    }

    HopperOptimizationModule hopperModule() {
        if (plugin.getModuleManager() == null) {
            return null;
        }
        OptimizationModule module = plugin.getModuleManager().getModule("hopper-optimization");
        return module instanceof HopperOptimizationModule h ? h : null;
    }

    /**
     * Villagers and wandering traders. Checked before {@link #isMob} because
     * {@code AbstractVillager} is itself a {@code Mob}, and the two lines are reported
     * separately.
     */
    private static boolean isVillager(EntityType type) {
        Class<? extends Entity> clazz = type.getEntityClass();
        return clazz != null && AbstractVillager.class.isAssignableFrom(clazz);
    }

    /** Anything with mob AI: hostile, passive, water, ambient. */
    private static boolean isMob(EntityType type) {
        Class<? extends Entity> clazz = type.getEntityClass();
        return clazz != null && Mob.class.isAssignableFrom(clazz);
    }
}
