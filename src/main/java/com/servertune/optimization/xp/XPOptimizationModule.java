package com.servertune.optimization.xp;

import com.servertune.ServerTunePlugin;
import com.servertune.core.ModuleState;
import com.servertune.core.OptimizationModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * XP orb merging and per-chunk caps.
 *
 * <p>Same cost model as the item module: one entity scan per world per cycle, merging done as a
 * cell-bucketed local sweep rather than an all-pairs comparison, and no {@link Location}
 * allocated per orb.
 */
public class XPOptimizationModule implements OptimizationModule {

    /** Floor on the merge cell size; see the item module for why. */
    private static final double MIN_CELL_SIZE = 0.5;

    /** 21 bits per axis in the packed cell key. */
    private static final long CELL_MASK = 0x1FFFFFL;

    /**
     * The thirteen forward neighbours of a cell in the 3x3x3 neighbourhood. Static: the sweep
     * allocates none of these.
     */
    private static final int[][] NEIGHBOUR_CELLS = {
            {0, 0, 1}, {0, 1, -1}, {0, 1, 0}, {0, 1, 1},
            {1, -1, -1}, {1, -1, 0}, {1, -1, 1},
            {1, 0, -1}, {1, 0, 0}, {1, 0, 1},
            {1, 1, -1}, {1, 1, 0}, {1, 1, 1}
    };

    private final ServerTunePlugin plugin;
    private ModuleState state = ModuleState.DISABLED;

    // Configuration
    private boolean mergingEnabled;
    private double mergeRadius;
    private int maxPerChunk;
    private final Set<String> disabledWorlds = new HashSet<>();

    /** Reused for every coordinate read. */
    private final Location scratch = new Location(null, 0, 0, 0);

    private record Positioned(ExperienceOrb orb, double x, double y, double z, long cell) {
    }

    public XPOptimizationModule(ServerTunePlugin plugin) {
        this.plugin = plugin;
        loadConfiguration();
    }

    private void loadConfiguration() {
        mergingEnabled = plugin.getConfig().getBoolean("optimization.modules.xp.merging.enabled", true);
        mergeRadius = plugin.getConfig().getDouble("optimization.modules.xp.merging.radius", 3.0);
        maxPerChunk = plugin.getConfig().getInt("optimization.modules.xp.max-per-chunk", 50);

        disabledWorlds.clear();
        disabledWorlds.addAll(plugin.getConfig().getStringList("optimization.modules.xp.disabled-worlds"));
    }

    @Override
    public String getName() {
        return "xp-optimization";
    }

    @Override
    public ModuleState getState() {
        return state;
    }

    @Override
    public void enable() {
        loadConfiguration();
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[XPOptimization] Enabled - merging: " + mergingEnabled);
    }

    @Override
    public void disable() {
        state = ModuleState.DISABLED;
        plugin.getLogger().info("[XPOptimization] Disabled");
    }

    @Override
    public void suspend() {
        state = ModuleState.SUSPENDED;
        plugin.getLogger().info("[XPOptimization] Suspended");
    }

    @Override
    public void resume() {
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[XPOptimization] Resumed");
    }

    @Override
    public void optimize() {
        if (state != ModuleState.ENABLED) {
            return;
        }

        // Both stages off means the world scan below buys nothing; see the item module. Without
        // this, merging: false plus max-per-chunk: 0 still ran getEntitiesByClass per world per
        // cycle and discarded the result.
        if (!mergingEnabled && maxPerChunk <= 0) {
            return;
        }

        try {
            int mergedCount = 0;
            int removedCount = 0;

            // Process each world
            for (World world : Bukkit.getWorlds()) {
                if (disabledWorlds.contains(world.getName())) {
                    continue;
                }

                List<ExperienceOrb> orbs = new ArrayList<>(world.getEntitiesByClass(ExperienceOrb.class));

                if (mergingEnabled) {
                    mergedCount += mergeOrbs(orbs);
                }

                removedCount += applyChunkLimits(orbs);
            }

            if (plugin.getConfigManager().isDebugEnabled() && (mergedCount > 0 || removedCount > 0)) {
                plugin.getLogger().info(String.format(
                    "[XPOptimization] Merged: %d, Removed: %d",
                    mergedCount, removedCount
                ));
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[XPOptimization] Error during optimization", e);
        }
    }

    /**
     * Cell-bucketed merge over the 3x3x3 neighbourhood; mirrors the item module, including the
     * binary-search neighbour lookup and the {@code consumed} array that keeps a merged-away orb
     * from being merged a second time.
     */
    private int mergeOrbs(List<ExperienceOrb> orbs) {
        double cellSize = Math.max(mergeRadius, MIN_CELL_SIZE);

        List<Positioned> candidates = new ArrayList<>(orbs.size());
        for (ExperienceOrb orb : orbs) {
            if (!orb.isValid()) {
                continue;
            }
            orb.getLocation(scratch);
            candidates.add(new Positioned(orb, scratch.getX(), scratch.getY(), scratch.getZ(),
                    cellKey(cellIndex(scratch.getX(), cellSize),
                            cellIndex(scratch.getY(), cellSize),
                            cellIndex(scratch.getZ(), cellSize))));
        }

        if (candidates.size() < 2) {
            return 0;
        }

        candidates.sort(Comparator.comparingLong(Positioned::cell));

        double radiusSquared = mergeRadius * mergeRadius;
        // As in the item module: absorbed orbs are tracked explicitly rather than trusting that
        // remove() makes isValid() false within the same tick. Merging an already-merged orb
        // would duplicate XP, so this is not a check worth economising on.
        boolean[] consumed = new boolean[candidates.size()];
        int mergedCount = 0;

        for (int i = 0; i < candidates.size(); i++) {
            if (consumed[i]) {
                continue;
            }

            Positioned first = candidates.get(i);
            if (!first.orb().isValid()) {
                continue;
            }

            int cx = cellIndex(first.x(), cellSize);
            int cy = cellIndex(first.y(), cellSize);
            int cz = cellIndex(first.z(), cellSize);

            for (int n = 0; n <= NEIGHBOUR_CELLS.length; n++) {
                long cell;
                int start;

                if (n == 0) {
                    cell = first.cell();
                    start = i + 1;
                } else {
                    int[] offset = NEIGHBOUR_CELLS[n - 1];
                    cell = cellKey(cx + offset[0], cy + offset[1], cz + offset[2]);
                    start = firstInCell(candidates, cell);
                    if (start < 0) {
                        continue;
                    }
                }

                for (int j = start; j < candidates.size(); j++) {
                    Positioned second = candidates.get(j);
                    if (second.cell() != cell) {
                        break;
                    }
                    if (consumed[j] || !second.orb().isValid()) {
                        continue;
                    }

                    double dx = second.x() - first.x();
                    double dy = second.y() - first.y();
                    double dz = second.z() - first.z();
                    if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                        continue;
                    }

                    first.orb().setExperience(first.orb().getExperience() + second.orb().getExperience());
                    second.orb().remove();
                    consumed[j] = true;
                    mergedCount++;
                }
            }
        }

        return mergedCount;
    }

    /**
     * Index of the first candidate in the given cell, or -1 when the cell is empty. The list is
     * sorted by cell key, so this is a plain binary search - no extra index structure, no
     * per-lookup allocation.
     */
    private static int firstInCell(List<Positioned> sorted, long cell) {
        int low = 0;
        int high = sorted.size() - 1;
        int found = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midCell = sorted.get(mid).cell();
            if (midCell < cell) {
                low = mid + 1;
            } else if (midCell > cell) {
                high = mid - 1;
            } else {
                found = mid;
                high = mid - 1;
            }
        }

        return found;
    }

    private int applyChunkLimits(List<ExperienceOrb> orbs) {
        if (maxPerChunk <= 0) {
            return 0;
        }

        Map<Long, List<ExperienceOrb>> chunkOrbs = new HashMap<>();

        for (ExperienceOrb orb : orbs) {
            if (!orb.isValid()) {
                continue;
            }

            orb.getLocation(scratch);
            long chunkKey = getChunkKey(scratch.getBlockX() >> 4, scratch.getBlockZ() >> 4);
            chunkOrbs.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(orb);
        }

        int removedCount = 0;

        for (List<ExperienceOrb> chunkOrbList : chunkOrbs.values()) {
            if (chunkOrbList.size() > maxPerChunk) {
                removedCount += removeSmallestOrbs(chunkOrbList, chunkOrbList.size() - maxPerChunk);
            }
        }

        return removedCount;
    }

    private int removeSmallestOrbs(List<ExperienceOrb> orbs, int count) {
        // Smallest first: dropping a 1-XP orb costs a player almost nothing.
        orbs.sort(Comparator.comparingInt(ExperienceOrb::getExperience));

        int removedCount = 0;
        for (ExperienceOrb orb : orbs) {
            if (removedCount >= count) {
                break;
            }
            if (orb.isValid()) {
                orb.remove();
                removedCount++;
            }
        }

        return removedCount;
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Cell index for one axis; {@code Math.floor} so negative coordinates bucket consistently. */
    static int cellIndex(double coordinate, double cellSize) {
        return (int) Math.floor(coordinate / cellSize);
    }

    /** Packs three cell indices into one long, 21 bits per axis. See the item module. */
    static long cellKey(int x, int y, int z) {
        return ((long) x & CELL_MASK) << 42
                | ((long) y & CELL_MASK) << 21
                | ((long) z & CELL_MASK);
    }
}
