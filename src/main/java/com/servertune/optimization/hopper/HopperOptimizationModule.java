package com.servertune.optimization.hopper;

import com.servertune.ServerTunePlugin;
import com.servertune.blockentity.BlockEntityStats;
import com.servertune.blockentity.HopperHotspot;
import com.servertune.core.ModuleState;
import com.servertune.core.OptimizationModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Hopper optimization.
 *
 * <p><b>API limits, stated plainly.</b> This plugin uses only the Paper API — no NMS. Vanilla's
 * 8-tick hopper transfer interval and the hopper block entity's per-tick container search are
 * server internals, reachable through spigot.yml (ticks-per.hopper-transfer, ticks-per.hopper-check)
 * or NMS, not through Bukkit. This module therefore cannot make a hopper tick less often.
 *
 * <p>What it does do is cancel transfer and pickup work through the cancellable events the API
 * does expose, which removes the item movement and container churn even though the block entity
 * still ticks. Idle hoppers fire no events at all, so inactive hoppers can only be reported,
 * not throttled; that is why inactive handling here is monitoring rather than a knob.
 */
public class HopperOptimizationModule implements OptimizationModule, Listener {

    /** The six faces a hopper can connect through. Static so the flood fill allocates none. */
    private static final BlockFace[] ADJACENT_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private final ServerTunePlugin plugin;
    private ModuleState state = ModuleState.DISABLED;
    private boolean listenerRegistered = false;

    // Configuration
    private final Set<String> disabledWorlds = new HashSet<>();
    private boolean throttleEnabled;
    private int minTicksBetweenTransfers;
    private boolean throttlePickups;
    private boolean perChunkLimitEnabled;
    private int maxHoppersPerChunk;
    private boolean networkLimitEnabled;
    private int maxNetworkSize;
    private int networkScanCap;
    private boolean hotspotDetectionEnabled;
    private int hotspotHopperThreshold;
    private int hotspotTransferThreshold;
    private int inactiveThresholdSeconds;
    private boolean logHotspots;

    // Per-hopper last transfer tick, keyed by packed block location
    private final Map<Long, Integer> lastTransferTick = new ConcurrentHashMap<>();

    // Latest computed hotspots, refreshed by optimize()
    private volatile List<HopperHotspot> hotspots = List.of();

    // Counters for reporting
    private volatile long throttledTransfers = 0L;
    private volatile long throttledPickups = 0L;
    private volatile long deniedPlacements = 0L;

    public HopperOptimizationModule(ServerTunePlugin plugin) {
        this.plugin = plugin;
        loadConfiguration();
    }

    private void loadConfiguration() {
        String base = "optimization.modules.hopper.";
        var config = plugin.getConfig();

        throttleEnabled = config.getBoolean(base + "transfer-throttle.enabled", false);
        minTicksBetweenTransfers = config.getInt(base + "transfer-throttle.min-ticks-between-transfers", 8);
        throttlePickups = config.getBoolean(base + "transfer-throttle.throttle-item-pickups", false);

        perChunkLimitEnabled = config.getBoolean(base + "per-chunk-limit.enabled", false);
        maxHoppersPerChunk = config.getInt(base + "per-chunk-limit.max-hoppers", 64);

        networkLimitEnabled = config.getBoolean(base + "network-limit.enabled", false);
        maxNetworkSize = config.getInt(base + "network-limit.max-connected-hoppers", 64);
        networkScanCap = config.getInt(base + "network-limit.scan-cap", 256);

        hotspotDetectionEnabled = config.getBoolean(base + "hotspot-detection.enabled", true);
        hotspotHopperThreshold = config.getInt(base + "hotspot-detection.hoppers-per-chunk", 24);
        hotspotTransferThreshold = config.getInt(base + "hotspot-detection.transfers-per-window", 500);
        logHotspots = config.getBoolean(base + "hotspot-detection.log-to-console", false);

        inactiveThresholdSeconds = config.getInt(base + "inactive-reporting.threshold-seconds", 300);

        disabledWorlds.clear();
        disabledWorlds.addAll(config.getStringList(base + "disabled-worlds"));
    }

    @Override
    public String getName() {
        return "hopper-optimization";
    }

    @Override
    public ModuleState getState() {
        return state;
    }

    @Override
    public void enable() {
        loadConfiguration();

        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }

        state = ModuleState.ENABLED;
        plugin.getLogger().info(String.format(
                "[HopperOptimization] Enabled - throttle: %s (%d ticks), per-chunk limit: %s (%d), network limit: %s (%d)",
                throttleEnabled, minTicksBetweenTransfers, perChunkLimitEnabled, maxHoppersPerChunk,
                networkLimitEnabled, maxNetworkSize));

        if (throttleEnabled && minTicksBetweenTransfers <= 8) {
            plugin.getLogger().info("[HopperOptimization] Throttle interval is at or below the vanilla "
                    + "8-tick transfer rate, so it will rarely cancel anything. Raise it to throttle.");
        }
    }

    @Override
    public void disable() {
        // Unregister before flipping state. The state check at the top of every handler stopped
        // them doing work, but the handlers stayed on Bukkit's lists for the life of the server:
        // three of them (transfer, pickup, place) are per-event hot paths that kept paying
        // dispatch cost for a module the operator had turned off.
        if (listenerRegistered) {
            HandlerList.unregisterAll(this);
            listenerRegistered = false;
        }
        state = ModuleState.DISABLED;
        lastTransferTick.clear();
        hotspots = List.of();
        plugin.getLogger().info("[HopperOptimization] Disabled");
    }

    @Override
    public void suspend() {
        state = ModuleState.SUSPENDED;
        // Drop the throttle cache on the way in. Its only pruning lives in optimize(), which
        // returns immediately unless the state is ENABLED, so a module suspended by the budget
        // enforcer or the guard kept a per-hopper map that nothing would ever shrink again -
        // and a suspended module is not auto-resumed. Clearing is O(size) once here rather than
        // a new scheduled scan; correctness is unaffected because the throttle check treats a
        // missing entry as "no recent transfer", the same answer a stale entry gives after the
        // interval elapses.
        lastTransferTick.clear();
        plugin.getLogger().info("[HopperOptimization] Suspended");
    }

    @Override
    public void resume() {
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[HopperOptimization] Resumed");
    }

    /**
     * Rolls the transfer sampling window and recomputes hotspots from cached counts.
     * Reads only tracker state; performs no world scan.
     */
    @Override
    public void optimize() {
        if (state != ModuleState.ENABLED) {
            return;
        }

        try {
            List<HopperHotspot> found = new ArrayList<>();

            for (World world : Bukkit.getWorlds()) {
                Map<Long, BlockEntityStats> worldStats =
                        plugin.getBlockEntityTracker().getWorldStats(world.getName());
                if (worldStats == null) {
                    continue;
                }

                for (BlockEntityStats stats : worldStats.values()) {
                    long windowTransfers = stats.resetWindow();

                    if (!hotspotDetectionEnabled) {
                        continue;
                    }

                    boolean concentrated = stats.getHopperCount() >= hotspotHopperThreshold;
                    boolean busy = windowTransfers >= hotspotTransferThreshold;

                    if (!concentrated && !busy) {
                        continue;
                    }

                    HopperHotspot.Reason reason = concentrated && busy
                            ? HopperHotspot.Reason.BOTH
                            : concentrated ? HopperHotspot.Reason.CONCENTRATION
                            : HopperHotspot.Reason.TRANSFER_VOLUME;

                    found.add(new HopperHotspot(world.getName(), stats.getChunkX(), stats.getChunkZ(),
                            stats.getHopperCount(), windowTransfers, reason));
                }
            }

            found.sort(Comparator.comparingLong(HopperHotspot::transfersPerWindow).reversed());
            hotspots = List.copyOf(found);

            pruneThrottleCache();

            if (logHotspots && !found.isEmpty()) {
                plugin.getLogger().info(String.format(
                        "[HopperOptimization] %d hotspot chunk(s); busiest: %s",
                        found.size(), found.get(0).describe()));
            }

            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info(String.format(
                        "[HopperOptimization] Window rolled - hotspots: %d, throttled transfers: %d, "
                                + "throttled pickups: %d, denied placements: %d",
                        found.size(), throttledTransfers, throttledPickups, deniedPlacements));
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[HopperOptimization] Error during optimization", e);
        }
    }

    /** Drops throttle entries for hoppers that have not moved anything in a while. */
    private void pruneThrottleCache() {
        if (lastTransferTick.isEmpty()) {
            return;
        }

        int now = Bukkit.getCurrentTick();
        int staleAfter = inactiveThresholdSeconds * 20;
        lastTransferTick.entrySet().removeIf(entry -> now - entry.getValue() > staleAfter);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (state != ModuleState.ENABLED) {
            return;
        }

        Inventory source = event.getSource();
        if (source.getType() != org.bukkit.event.inventory.InventoryType.HOPPER) {
            return;
        }

        Location location = inventoryLocation(source);
        if (location == null || !isWorldEnabled(location.getWorld())) {
            return;
        }

        recordTransfer(location);

        if (!throttleEnabled) {
            return;
        }

        if (shouldThrottle(location)) {
            event.setCancelled(true);
            throttledTransfers++;
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        if (state != ModuleState.ENABLED || !throttleEnabled || !throttlePickups) {
            return;
        }

        Inventory inventory = event.getInventory();
        if (inventory.getType() != org.bukkit.event.inventory.InventoryType.HOPPER) {
            return;
        }

        Location location = inventoryLocation(inventory);
        if (location == null || !isWorldEnabled(location.getWorld())) {
            return;
        }

        if (shouldThrottle(location)) {
            event.setCancelled(true);
            throttledPickups++;
        }
    }

    /**
     * Returns true when this hopper moved something too recently. Updates the timestamp when
     * the transfer is allowed through.
     */
    private boolean shouldThrottle(Location location) {
        long key = packLocation(location);
        int now = Bukkit.getCurrentTick();
        Integer last = lastTransferTick.get(key);

        if (last != null && now - last < minTicksBetweenTransfers) {
            return true;
        }

        lastTransferTick.put(key, now);
        return false;
    }

    private void recordTransfer(Location location) {
        plugin.getBlockEntityTracker().recordTransfer(
                location.getWorld().getName(),
                location.getBlockX() >> 4,
                location.getBlockZ() >> 4,
                Bukkit.getCurrentTick());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (state != ModuleState.ENABLED) {
            return;
        }

        Block block = event.getBlock();
        if (block.getType() != Material.HOPPER || !isWorldEnabled(block.getWorld())) {
            return;
        }

        if (perChunkLimitEnabled && exceedsChunkLimit(block)) {
            event.setCancelled(true);
            deniedPlacements++;
            event.getPlayer().sendMessage("§cHopper limit reached for this chunk (max "
                    + maxHoppersPerChunk + ").");
            return;
        }

        if (networkLimitEnabled && exceedsNetworkLimit(block)) {
            event.setCancelled(true);
            deniedPlacements++;
            event.getPlayer().sendMessage("§cConnected hopper limit reached (max "
                    + maxNetworkSize + ").");
        }
    }

    private boolean exceedsChunkLimit(Block block) {
        BlockEntityStats stats = plugin.getBlockEntityTracker().getStats(
                block.getWorld().getName(), block.getChunk().getX(), block.getChunk().getZ());

        int current = stats == null ? 0 : stats.getHopperCount();
        return current + 1 > maxHoppersPerChunk;
    }

    /**
     * Bounded flood fill over touching hoppers. Runs once per placement and stops at
     * scanCap blocks, so a large existing network cannot turn one placement into a long scan.
     */
    private boolean exceedsNetworkLimit(Block placed) {
        Set<Long> visited = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();

        queue.add(placed);
        visited.add(packLocation(placed.getLocation()));

        int connected = 0;

        while (!queue.isEmpty() && visited.size() < networkScanCap) {
            Block current = queue.poll();
            connected++;

            if (connected > maxNetworkSize) {
                return true;
            }

            for (BlockFace face : ADJACENT_FACES) {
                Block neighbor = current.getRelative(face);

                // Only walk loaded chunks, so this never triggers chunk generation
                if (!neighbor.getWorld().isChunkLoaded(neighbor.getX() >> 4, neighbor.getZ() >> 4)) {
                    continue;
                }

                if (neighbor.getType() != Material.HOPPER) {
                    continue;
                }

                if (visited.add(packLocation(neighbor.getLocation()))) {
                    queue.add(neighbor);
                }
            }
        }

        return false;
    }

    /**
     * Chunks holding hoppers that have not transferred anything within the configured
     * threshold. Reporting only — the API cannot throttle a hopper that fires no events.
     */
    public List<BlockEntityStats> getInactiveHopperChunks() {
        List<BlockEntityStats> inactive = new ArrayList<>();
        int now = Bukkit.getCurrentTick();
        int threshold = inactiveThresholdSeconds * 20;

        for (World world : Bukkit.getWorlds()) {
            Map<Long, BlockEntityStats> worldStats =
                    plugin.getBlockEntityTracker().getWorldStats(world.getName());
            if (worldStats == null) {
                continue;
            }

            for (BlockEntityStats stats : worldStats.values()) {
                if (stats.getHopperCount() == 0) {
                    continue;
                }

                int last = stats.getLastTransferTick();
                if (last < 0 || now - last > threshold) {
                    inactive.add(stats);
                }
            }
        }

        return inactive;
    }

    private Location inventoryLocation(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof org.bukkit.block.BlockState blockState) {
            return blockState.getLocation();
        }
        return inventory.getLocation();
    }

    /**
     * Reads a cached set, not the config.
     *
     * <p>{@code InventoryMoveItemEvent} fires for every hopper transfer on the server, so a
     * {@code getStringList} here - which re-parses and copies the list on each call - was one
     * of the hottest allocations in the plugin.
     */
    private boolean isWorldEnabled(World world) {
        if (world == null) {
            return false;
        }
        return !disabledWorlds.contains(world.getName());
    }

    /**
     * Packs a block location into one long: X in bits 38-63, Z in bits 12-37, Y in bits 0-11.
     * 26 + 26 + 12 is exactly 64, so there is no spare bit.
     *
     * <p>The 12-bit Y mask looks lossy and is not, for any coordinate Minecraft permits. A
     * dimension's {@code min_y} is at least -2032 and {@code min_y + height} is at most 2032, so
     * Y runs -2032..2031. Under the mask, negative Y lands in 2064..4095 and non-negative Y in
     * 0..2031 - disjoint, with 2032..2063 unused, which also absorbs the one-block overshoot the
     * flood fill produces at {@code getRelative(UP)} on the top layer. 26 bits of X/Z is likewise
     * the minimum for the +/-29,999,984 world border.
     *
     * <p>Do not widen Y to 13 bits: it would take bit 12 from Z and alias every pair of blocks
     * 4096 apart in Z, turning a non-issue into a real collision.
     */
    private long packLocation(Location location) {
        return ((long) location.getBlockX() & 0x3FFFFFF) << 38
                | ((long) location.getBlockZ() & 0x3FFFFFF) << 12
                | ((long) location.getBlockY() & 0xFFF);
    }

    public List<HopperHotspot> getHotspots() {
        return hotspots;
    }

    public long getThrottledTransfers() {
        return throttledTransfers;
    }

    public long getThrottledPickups() {
        return throttledPickups;
    }

    public long getDeniedPlacements() {
        return deniedPlacements;
    }

    public boolean isThrottleEnabled() {
        return throttleEnabled;
    }
}
