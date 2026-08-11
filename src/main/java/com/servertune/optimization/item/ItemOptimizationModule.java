package com.servertune.optimization.item;

import com.servertune.ServerTunePlugin;
import com.servertune.core.ModuleState;
import com.servertune.core.OptimizationModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Dropped-item optimization.
 *
 * <p><b>Cost notes.</b> One {@code getEntitiesByClass} scan per world per cycle, not three.
 * The earlier version rescanned the world after merging and again after the chunk pass,
 * tripling the only genuinely expensive call here. Every stage now works from that one list
 * and skips entities {@code isValid()} no longer accepts.
 *
 * <p>Merging buckets items into cubic cells of the merge radius, so each item is compared only
 * against its own cell and the thirteen forward-neighbour cells. The previous nested loop compared
 * every pair in the world and allocated a fresh {@link Location} per comparison.
 *
 * <p>{@code protected-types} and {@code disabled-worlds} resolve once in
 * {@link #loadConfiguration()}. Read through {@code getStringList} per item, as before, they
 * re-parsed a config list thousands of times per cycle.
 */
public class ItemOptimizationModule implements OptimizationModule {

    /**
     * Floor on the merge cell size. A radius near zero would otherwise produce cell indices large
     * enough to wrap the 21-bit packing, and a cell far smaller than an entity is pointless work.
     */
    private static final double MIN_CELL_SIZE = 0.5;

    /** 21 bits per axis in the packed cell key. */
    private static final long CELL_MASK = 0x1FFFFFL;

    /**
     * The thirteen forward neighbours of a cell in the 3x3x3 neighbourhood - the half that sorts
     * after the centre. Their backward counterparts are reached from the other item's own pass, so
     * every pair is examined exactly once. Static: the sweep allocates none of these.
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
    private int itemLifetimeTicks;
    private int maxItemsPerChunk;
    private boolean hasGlobalLimit;
    private int globalMaxItems;
    private final Set<String> protectedTypes = new HashSet<>();
    private final Set<String> disabledWorlds = new HashSet<>();

    /** Reused for every coordinate read, so no cycle allocates a Location per entity. */
    private final Location scratch = new Location(null, 0, 0, 0);

    private record Positioned(Item item, double x, double y, double z, long cell) {
    }

    private enum MergeResult {
        NOT_MERGEABLE,
        MERGED,
        /** Partial merge: the target stack is now full and cannot absorb anything else. */
        TARGET_FULL
    }

    public ItemOptimizationModule(ServerTunePlugin plugin) {
        this.plugin = plugin;
        loadConfiguration();
    }

    private void loadConfiguration() {
        String base = "optimization.modules.item.";
        mergingEnabled = plugin.getConfig().getBoolean(base + "merging.enabled", true);
        mergeRadius = plugin.getConfig().getDouble(base + "merging.radius", 3.0);
        itemLifetimeTicks = plugin.getConfig().getInt(base + "lifetime-ticks", 6000);
        maxItemsPerChunk = plugin.getConfig().getInt(base + "max-per-chunk", 100);
        hasGlobalLimit = plugin.getConfig().getBoolean(base + "global-limit.enabled", false);
        globalMaxItems = plugin.getConfig().getInt(base + "global-limit.max", 5000);

        protectedTypes.clear();
        protectedTypes.addAll(plugin.getConfig().getStringList(base + "protected-types"));

        disabledWorlds.clear();
        disabledWorlds.addAll(plugin.getConfig().getStringList(base + "disabled-worlds"));
    }

    @Override
    public String getName() {
        return "item-optimization";
    }

    @Override
    public ModuleState getState() {
        return state;
    }

    @Override
    public void enable() {
        loadConfiguration();
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[ItemOptimization] Enabled - merging: " + mergingEnabled);
    }

    @Override
    public void disable() {
        state = ModuleState.DISABLED;
        plugin.getLogger().info("[ItemOptimization] Disabled");
    }

    @Override
    public void suspend() {
        state = ModuleState.SUSPENDED;
        plugin.getLogger().info("[ItemOptimization] Suspended");
    }

    @Override
    public void resume() {
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[ItemOptimization] Resumed");
    }

    @Override
    public void optimize() {
        if (state != ModuleState.ENABLED) {
            return;
        }

        // Every stage below is individually switchable, and each already no-ops when its own
        // setting is off - but only after the world scan. With merging off, no global limit, and
        // both max-per-chunk and lifetime-ticks at 0, the module still ran getEntitiesByClass over
        // every world each cycle and threw the list away. That is the single most expensive call
        // here, so an operator who had turned everything off was paying full price for nothing.
        if (!mergingEnabled && !hasGlobalLimit && maxItemsPerChunk <= 0 && itemLifetimeTicks <= 0) {
            return;
        }

        try {
            boolean debug = plugin.getConfigManager().isDebugEnabled();
            int mergedCount = 0;
            int removedCount = 0;

            for (World world : Bukkit.getWorlds()) {
                if (disabledWorlds.contains(world.getName())) {
                    continue;
                }

                // The one expensive call in this module. Everything below reuses this list.
                List<Item> items = new ArrayList<>(world.getEntitiesByClass(Item.class));

                if (hasGlobalLimit && items.size() > globalMaxItems) {
                    removedCount += removeOldest(items, items.size() - globalMaxItems);
                }

                if (mergingEnabled) {
                    mergedCount += mergeItems(items);
                }

                removedCount += applyChunkLimits(items);
                removedCount += cleanupOldItems(items);

                if (debug) {
                    plugin.getLogger().info(String.format(
                            "[ItemOptimization] %s: scanned %d items",
                            world.getName(), items.size()));
                }
            }

            if (debug && (mergedCount > 0 || removedCount > 0)) {
                plugin.getLogger().info(String.format(
                        "[ItemOptimization] Merged: %d, Removed: %d", mergedCount, removedCount));
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[ItemOptimization] Error during optimization", e);
        }
    }
    /**
     * Merges stackable items within {@code merging.radius}.
     *
     * <p>Candidates are bucketed into cubic cells of the merge radius and sorted by cell key, so
     * each item is compared only against its own cell and the thirteen forward-neighbour cells.
     * Two items closer than the radius always land in the same cell or in cells one step apart on
     * every axis, so the set of eligible pairs is exactly the set the old X-sorted sweep
     * considered.
     *
     * <p>Why it changed: that sweep bounded the scan on X alone. Any dense band of items sharing
     * an X coordinate - a farm collection point, a grinder, a lag machine - left the inner loop
     * running the full width of the band, which is all-pairs on the main thread. Bounding on all
     * three axes removes the quadratic term for spread-out items.
     *
     * <p>Nothing extra is allocated to do it: neighbour cells are found by binary search over the
     * list that was already being sorted, and the {@code boolean[] consumed} array is the same one
     * allocation the all-pairs version made.
     */
    private int mergeItems(List<Item> items) {
        double cellSize = Math.max(mergeRadius, MIN_CELL_SIZE);

        List<Positioned> candidates = new ArrayList<>(items.size());
        for (Item item : items) {
            if (!item.isValid() || isProtected(item)) {
                continue;
            }
            item.getLocation(scratch);
            candidates.add(new Positioned(item, scratch.getX(), scratch.getY(), scratch.getZ(),
                    cellKey(cellIndex(scratch.getX(), cellSize),
                            cellIndex(scratch.getY(), cellSize),
                            cellIndex(scratch.getZ(), cellSize))));
        }

        if (candidates.size() < 2) {
            return 0;
        }

        candidates.sort(Comparator.comparingLong(Positioned::cell));

        double radiusSquared = mergeRadius * mergeRadius;
        // Kept from the pre-cell version. isValid() alone would very probably do, but relying on
        // it means trusting that remove() is reflected in the same tick; if it ever were not, an
        // already-merged stack could be merged a second time and duplicate items. The array is one
        // allocation the previous implementation also made, so this costs nothing to be sure about.
        boolean[] consumed = new boolean[candidates.size()];
        int mergedCount = 0;

        for (int i = 0; i < candidates.size(); i++) {
            if (consumed[i]) {
                continue;
            }

            Positioned first = candidates.get(i);
            if (!first.item().isValid()) {
                continue;
            }

            int cx = cellIndex(first.x(), cellSize);
            int cy = cellIndex(first.y(), cellSize);
            int cz = cellIndex(first.z(), cellSize);
            boolean targetFull = false;

            // n == 0 is this item's own cell, scanned forward from i so a pair is never examined
            // twice; n > 0 are the thirteen forward neighbours, which together with the backward
            // thirteen reached from the other side cover the full 3x3x3 neighbourhood exactly once.
            for (int n = 0; !targetFull && n <= NEIGHBOUR_CELLS.length; n++) {
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
                    if (consumed[j] || !second.item().isValid()) {
                        continue;
                    }

                    double dx = second.x() - first.x();
                    double dy = second.y() - first.y();
                    double dz = second.z() - first.z();
                    if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                        continue;
                    }

                    MergeResult result = tryMerge(first.item(), second.item());
                    if (result == MergeResult.NOT_MERGEABLE) {
                        continue;
                    }

                    mergedCount++;
                    if (result == MergeResult.MERGED) {
                        consumed[j] = true;
                    } else {
                        // first is full; it cannot absorb anything else this pass.
                        targetFull = true;
                        break;
                    }
                }
            }
        }

        return mergedCount;
    }

    /**
     * Index of the first candidate in the given cell, or -1 when no candidate is in it. The list
     * is sorted by cell key, so this is a plain binary search - no per-lookup allocation, and no
     * separate cell index to build or keep in step.
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

    private MergeResult tryMerge(Item target, Item source) {
        ItemStack targetStack = target.getItemStack();
        ItemStack sourceStack = source.getItemStack();

        if (!targetStack.isSimilar(sourceStack)) {
            return MergeResult.NOT_MERGEABLE;
        }

        int combined = targetStack.getAmount() + sourceStack.getAmount();
        int maxStackSize = targetStack.getMaxStackSize();

        if (combined <= maxStackSize) {
            targetStack.setAmount(combined);
            target.setItemStack(targetStack);
            source.remove();
            return MergeResult.MERGED;
        }

        targetStack.setAmount(maxStackSize);
        target.setItemStack(targetStack);
        sourceStack.setAmount(combined - maxStackSize);
        source.setItemStack(sourceStack);
        return MergeResult.TARGET_FULL;
    }

    /**
     * Groups by chunk using coordinate arithmetic. {@code Location#getChunk()} would load or
     * resolve a chunk object per item; an item's chunk is just its block coordinates shifted.
     */
    private int applyChunkLimits(List<Item> items) {
        if (maxItemsPerChunk <= 0) {
            return 0;
        }

        Map<Long, List<Item>> byChunk = new HashMap<>();

        for (Item item : items) {
            if (!item.isValid() || isProtected(item)) {
                continue;
            }

            item.getLocation(scratch);
            long key = getChunkKey(scratch.getBlockX() >> 4, scratch.getBlockZ() >> 4);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        int removedCount = 0;
        for (List<Item> chunkItems : byChunk.values()) {
            if (chunkItems.size() > maxItemsPerChunk) {
                removedCount += removeOldest(chunkItems, chunkItems.size() - maxItemsPerChunk);
            }
        }

        return removedCount;
    }

    private int cleanupOldItems(List<Item> items) {
        if (itemLifetimeTicks <= 0) {
            return 0;
        }

        int removedCount = 0;
        for (Item item : items) {
            if (!item.isValid() || isProtected(item)) {
                continue;
            }

            if (item.getTicksLived() > itemLifetimeTicks) {
                item.remove();
                removedCount++;
            }
        }

        return removedCount;
    }

    /** Removes the {@code count} longest-lived unprotected items from the given list. */
    private int removeOldest(List<Item> items, int count) {
        if (count <= 0) {
            return 0;
        }

        items.sort(Comparator.comparingInt(Item::getTicksLived).reversed());

        int removedCount = 0;
        for (Item item : items) {
            if (removedCount >= count) {
                break;
            }
            if (item.isValid() && !isProtected(item)) {
                item.remove();
                removedCount++;
            }
        }

        return removedCount;
    }

    private boolean isProtected(Item item) {
        if (item.customName() != null) {
            return true;
        }
        return !protectedTypes.isEmpty()
                && protectedTypes.contains(item.getItemStack().getType().name());
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Cell index for one axis. Uses {@code Math.floor}, not integer division: division truncates
     * toward zero, which would make -0.5 and 0.5 share a cell while -1.5 and -0.5 did not, and a
     * cell boundary that behaves differently either side of zero misses merges near x=0.
     */
    static int cellIndex(double coordinate, double cellSize) {
        return (int) Math.floor(coordinate / cellSize);
    }

    /**
     * Packs three cell indices into one long for grouping, 21 bits per axis.
     *
     * <p>21 bits does not cover the full world border at every radius - at the default 3-block
     * radius it is exact out to roughly +/-3.1 million blocks, and beyond that two far-apart cells
     * can share a key. That is deliberate and harmless: a shared key only puts extra candidates in
     * front of the distance check, which rejects them. It cannot cause a wrong merge, and it
     * cannot cause a missed one, because both items of a nearby pair derive their key from the
     * same arithmetic and so always collide the same way.
     */
    static long cellKey(int x, int y, int z) {
        return ((long) x & CELL_MASK) << 42
                | ((long) y & CELL_MASK) << 21
                | ((long) z & CELL_MASK);
    }
}
