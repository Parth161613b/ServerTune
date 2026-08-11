package com.servertune.optimization.redstone;

import com.servertune.ServerTunePlugin;
import com.servertune.core.ModuleState;
import com.servertune.core.OptimizationModule;
import com.servertune.redstone.RedstoneActivity;
import com.servertune.redstone.RedstoneHotspot;
import com.servertune.redstone.RedstoneMode;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import io.papermc.paper.event.block.BlockPreDispenseEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Redstone monitoring and optional limiting.
 *
 * <p><b>What this module can and cannot do.</b> Verified against the paper-api jar on the
 * compile classpath with javap; see docs/REDSTONE-API-NOTES.md.
 *
 * <ul>
 *   <li>{@code BlockPistonExtendEvent} / {@code BlockPistonRetractEvent} are cancellable,
 *       so piston limits are real enforcement.</li>
 *   <li>{@code BlockPreDispenseEvent} and {@code BlockDispenseEvent} are cancellable, so
 *       dispenser/dropper limits are real enforcement.</li>
 *   <li>{@code BlockRedstoneEvent} is <b>NOT</b> cancellable - it exposes only
 *       {@code setNewCurrent(int)}. There is no safe way to limit redstone updates per
 *       tick through the API, so {@code max-updates-per-tick} is deliberately absent
 *       rather than faked.</li>
 *   <li>Observers have no dedicated event. They are monitored via
 *       {@code BlockPhysicsEvent#getSourceBlock()} and never limited, because cancelling
 *       block physics detaches torches and desyncs clients.</li>
 * </ul>
 *
 * <p><b>Performance.</b> All counters are per-chunk and updated in O(1) from events. This
 * module never scans for redstone blocks. {@code optimize()} touches only cached
 * counters. The {@code BlockPhysicsEvent} handler is opt-in because that event is
 * extremely high frequency, and it supports sampling.
 */
public class RedstoneOptimizationModule implements OptimizationModule, Listener {

    private final ServerTunePlugin plugin;
    private ModuleState state = ModuleState.DISABLED;
    private boolean listenerRegistered = false;

    private final Map<String, Map<Long, RedstoneActivity>> activityByWorld = new ConcurrentHashMap<>();
    private final AtomicLong physicsEventsSeen = new AtomicLong(0);

    private volatile List<RedstoneHotspot> lastHotspots = List.of();

    // Resolved configuration
    private RedstoneMode mode;
    private boolean pistonLimitEnabled;
    private int pistonPerSecond;
    private int pistonPerWindow;
    private int pistonCooldownTicks;
    private LimitAction pistonAction;
    private boolean dispenserLimitEnabled;
    private int dispenserPerSecond;
    private LimitAction dispenserAction;
    private boolean observerMonitoring;
    private int observerSampleRate;
    private boolean redstoneChangeMonitoring;
    private boolean hotspotDetection;
    private long hotspotThreshold;
    private boolean hotspotLogging;
    private int hotspotReportLimit;
    private int idleTicks;
    private List<String> disabledWorlds = List.of();

    /** What to do when a limit is exceeded. */
    public enum LimitAction {
        /** Count it, allow it. Zero gameplay impact. */
        MONITOR,
        /** Cancel the individual action over the limit. */
        CANCEL,
        /** Cancel and put the chunk's redstone on cooldown. */
        CANCEL_AND_COOLDOWN;

        static LimitAction fromString(String value, LimitAction fallback) {
            if (value == null) {
                return fallback;
            }
            try {
                return valueOf(value.trim().toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public RedstoneOptimizationModule(ServerTunePlugin plugin) {
        this.plugin = plugin;
        loadConfiguration();
    }

    private void loadConfiguration() {
        var config = plugin.getConfig();
        String base = "optimization.modules.redstone";

        mode = RedstoneMode.fromString(config.getString(base + ".mode", "conservative"),
                RedstoneMode.CONSERVATIVE);

        // Mode supplies the defaults; the custom section overrides whatever it sets.
        String custom = base + ".custom";

        pistonPerSecond = config.getInt(custom + ".piston.max-per-chunk-per-second",
                mode.getPistonPerChunkPerSecond());
        pistonPerWindow = config.getInt(custom + ".piston.max-per-chunk-per-window",
                mode.getPistonPerChunkPerWindow());
        pistonCooldownTicks = config.getInt(custom + ".piston.cooldown-ticks",
                mode.getPistonCooldownTicks());
        pistonAction = LimitAction.fromString(
                config.getString(custom + ".piston.action-on-limit"),
                mode.altersGameplay() ? LimitAction.CANCEL : LimitAction.MONITOR);
        pistonLimitEnabled = config.getBoolean(custom + ".piston.limit-enabled",
                mode.altersGameplay() && pistonPerSecond > 0);

        dispenserPerSecond = config.getInt(custom + ".dispenser.max-per-chunk-per-second",
                mode.getDispenserPerChunkPerSecond());
        dispenserAction = LimitAction.fromString(
                config.getString(custom + ".dispenser.action-on-limit"),
                mode.altersGameplay() ? LimitAction.CANCEL : LimitAction.MONITOR);
        dispenserLimitEnabled = config.getBoolean(custom + ".dispenser.limit-enabled",
                mode.isDispenserLimitEnabled() && dispenserPerSecond > 0);

        observerMonitoring = config.getBoolean(custom + ".observer.monitoring-enabled", false);
        observerSampleRate = Math.max(1, config.getInt(custom + ".observer.sample-rate", 8));

        redstoneChangeMonitoring = config.getBoolean(custom + ".redstone-change-monitoring", true);

        hotspotDetection = config.getBoolean(base + ".hotspot-detection.enabled", true);
        hotspotThreshold = config.getLong(base + ".hotspot-detection.activity-per-window", 600);
        hotspotLogging = config.getBoolean(base + ".hotspot-detection.log-to-console", false);
        hotspotReportLimit = config.getInt(base + ".hotspot-detection.max-reported", 10);

        idleTicks = config.getInt(base + ".idle-threshold-seconds", 300) * 20;
        disabledWorlds = config.getStringList(base + ".disabled-worlds");
    }

    @Override
    public String getName() {
        return "redstone-optimization";
    }

    @Override
    public ModuleState getState() {
        return state;
    }

    @Override
    public void enable() {
        loadConfiguration();

        if (mode == RedstoneMode.VANILLA) {
            state = ModuleState.DISABLED;
            plugin.getLogger().info("[RedstoneOptimization] Mode is VANILLA - module stays inactive, no listeners registered");
            return;
        }

        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }

        state = ModuleState.ENABLED;

        plugin.getLogger().info("[RedstoneOptimization] Enabled - mode: " + mode);

        if (pistonLimitEnabled) {
            plugin.getLogger().warning(String.format(
                    "[RedstoneOptimization] Piston limiting ACTIVE (%d/sec per chunk, action=%s). "
                            + "This alters gameplay: flying machines and fast technical farms may break.",
                    pistonPerSecond, pistonAction));
        }
        if (dispenserLimitEnabled) {
            plugin.getLogger().warning(String.format(
                    "[RedstoneOptimization] Dispenser limiting ACTIVE (%d/sec per chunk, action=%s). "
                            + "This alters gameplay: rapid dispenser farms may break.",
                    dispenserPerSecond, dispenserAction));
        }
        if (!pistonLimitEnabled && !dispenserLimitEnabled) {
            plugin.getLogger().info("[RedstoneOptimization] Monitoring only - nothing will be cancelled");
        }
        if (observerMonitoring) {
            plugin.getLogger().info("[RedstoneOptimization] Observer monitoring active via BlockPhysicsEvent (sample rate 1/"
                    + observerSampleRate + "). Observers are monitored, never limited.");
        }
    }

    @Override
    public void disable() {
        if (listenerRegistered) {
            HandlerList.unregisterAll(this);
            listenerRegistered = false;
        }
        state = ModuleState.DISABLED;
        activityByWorld.clear();
        lastHotspots = List.of();
        plugin.getLogger().info("[RedstoneOptimization] Disabled");
    }

    @Override
    public void suspend() {
        state = ModuleState.SUSPENDED;
        // Drop the per-chunk activity records on the way in, for the same reason the hopper
        // module drops its throttle cache: the only prune site is inside optimize(), which returns
        // immediately unless the state is ENABLED, so a suspended module held every record it had
        // accumulated with nothing able to shrink it - and a module suspended by the budget
        // enforcer is not auto-resumed. Clearing is O(size) once here, not a scan on a timer.
        //
        // It also prevents stale attribution on resume: the one-second and reporting windows are
        // tick-stamped, so counters carried across a long suspension would be rolled into the
        // first window after resume and reported as activity that happened while the module was
        // not watching. lastHotspots is deliberately kept - it is already labelled as the last
        // computed set, and disable() is the path that clears it.
        activityByWorld.clear();
        plugin.getLogger().info("[RedstoneOptimization] Suspended");
    }

    @Override
    public void resume() {
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[RedstoneOptimization] Resumed");
    }

    // ==================== Enforcement and tracking ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePiston(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePiston(event.getBlock(), event);
    }

    private void handlePiston(Block block, org.bukkit.event.Cancellable event) {
        if (state != ModuleState.ENABLED) {
            return;
        }

        try {
            if (isWorldDisabled(block.getWorld().getName())) {
                return;
            }

            int tick = Bukkit.getCurrentTick();
            RedstoneActivity activity = activityFor(block);

            if (pistonLimitEnabled && activity.isOnCooldown(tick)) {
                activity.recordBlocked();
                event.setCancelled(true);
                return;
            }

            int thisSecond = activity.recordPistonMove(tick);

            if (!pistonLimitEnabled || pistonAction == LimitAction.MONITOR) {
                return;
            }

            boolean overSecond = pistonPerSecond > 0 && thisSecond > pistonPerSecond;

            if (overSecond) {
                activity.recordBlocked();
                event.setCancelled(true);

                if (pistonAction == LimitAction.CANCEL_AND_COOLDOWN) {
                    activity.startCooldown(tick, pistonCooldownTicks);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[RedstoneOptimization] Error handling piston event", e);
        }
    }

    /**
     * Paper's pre-dispense event fires before the item is chosen, so it is the cheaper
     * interception point for rate limiting.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreDispense(BlockPreDispenseEvent event) {
        handleDispense(event.getBlock(), event);
    }

    /**
     * Fallback for dispense paths that do not route through the pre-dispense event.
     * Only counts when limiting is off, so a single action is never double counted.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (state != ModuleState.ENABLED || dispenserLimitEnabled) {
            return;
        }
        try {
            if (isWorldDisabled(event.getBlock().getWorld().getName())) {
                return;
            }
            Material type = event.getBlock().getType();
            if (type == Material.DISPENSER || type == Material.DROPPER) {
                activityFor(event.getBlock()).recordDispense(Bukkit.getCurrentTick());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[RedstoneOptimization] Error handling dispense event", e);
        }
    }

    private void handleDispense(Block block, org.bukkit.event.Cancellable event) {
        if (state != ModuleState.ENABLED) {
            return;
        }

        try {
            if (isWorldDisabled(block.getWorld().getName())) {
                return;
            }

            int tick = Bukkit.getCurrentTick();
            RedstoneActivity activity = activityFor(block);
            int thisSecond = activity.recordDispense(tick);

            if (!dispenserLimitEnabled || dispenserAction == LimitAction.MONITOR) {
                return;
            }

            if (dispenserPerSecond > 0 && thisSecond > dispenserPerSecond) {
                activity.recordBlocked();
                event.setCancelled(true);

                if (dispenserAction == LimitAction.CANCEL_AND_COOLDOWN) {
                    activity.startCooldown(tick, pistonCooldownTicks);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[RedstoneOptimization] Error handling dispense event", e);
        }
    }

    /**
     * Redstone signal changes. MONITOR priority and never cancelled - the event is not
     * Cancellable, and setNewCurrent would rewrite redstone logic rather than limit cost.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRedstoneChange(BlockRedstoneEvent event) {
        if (state != ModuleState.ENABLED || !redstoneChangeMonitoring) {
            return;
        }
        try {
            if (isWorldDisabled(event.getBlock().getWorld().getName())) {
                return;
            }
            activityFor(event.getBlock()).recordRedstoneChange(Bukkit.getCurrentTick());
        } catch (Exception e) {
            // Deliberately quiet: this event is very high frequency and a log storm here
            // would be worse than the lost sample.
        }
    }

    /**
     * Observer activity, inferred from the neighbour updates observers cause. Opt-in and
     * sampled because BlockPhysicsEvent fires for nearly every block update on the
     * server. Never cancels.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (state != ModuleState.ENABLED || !observerMonitoring) {
            return;
        }

        // Sample before touching anything else so the common path is one increment.
        if (physicsEventsSeen.incrementAndGet() % observerSampleRate != 0) {
            return;
        }

        try {
            Block source = event.getSourceBlock();
            if (source.getType() != Material.OBSERVER) {
                return;
            }
            if (isWorldDisabled(source.getWorld().getName())) {
                return;
            }
            activityFor(source).recordObserverUpdate(Bukkit.getCurrentTick());
        } catch (Exception e) {
            // Quiet for the same reason as above.
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Map<Long, RedstoneActivity> world = activityByWorld.get(event.getWorld().getName());
        if (world != null) {
            world.remove(chunkKey(event.getChunk().getX(), event.getChunk().getZ()));
        }
    }

    // ==================== Reporting cycle ====================

    @Override
    public void optimize() {
        if (state != ModuleState.ENABLED) {
            return;
        }

        try {
            List<RedstoneHotspot> hotspots = new ArrayList<>();
            int tick = Bukkit.getCurrentTick();

            for (Map<Long, RedstoneActivity> world : activityByWorld.values()) {
                world.values().removeIf(activity ->
                        activity.isIdle(tick, idleTicks) && activity.getLifetimeTotal() == 0);

                for (RedstoneActivity activity : world.values()) {
                    RedstoneActivity.Window window = activity.rollReportWindow();

                    if (!hotspotDetection || window.total() < hotspotThreshold) {
                        continue;
                    }

                    hotspots.add(new RedstoneHotspot(
                            activity.getWorldName(), activity.getChunkX(), activity.getChunkZ(),
                            window.pistonMoves(), window.dispenses(), window.observerUpdates(),
                            window.redstoneChanges(), activity.getBlockedActions()));
                }
            }

            hotspots.sort(Comparator.comparingLong(RedstoneHotspot::totalActivity).reversed());
            if (hotspots.size() > hotspotReportLimit) {
                hotspots = new ArrayList<>(hotspots.subList(0, hotspotReportLimit));
            }
            lastHotspots = List.copyOf(hotspots);

            if (hotspotLogging && !hotspots.isEmpty()) {
                plugin.getLogger().info("[RedstoneOptimization] " + hotspots.size() + " redstone hotspot(s):");
                for (RedstoneHotspot hotspot : hotspots) {
                    plugin.getLogger().info("  " + hotspot.describe());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[RedstoneOptimization] Error during reporting cycle", e);
        }
    }

    // ==================== Accessors ====================

    private RedstoneActivity activityFor(Block block) {
        String worldName = block.getWorld().getName();
        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;

        return activityByWorld
                .computeIfAbsent(worldName, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey(chunkX, chunkZ),
                        k -> new RedstoneActivity(worldName, chunkX, chunkZ));
    }

    private long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private boolean isWorldDisabled(String worldName) {
        return !disabledWorlds.isEmpty() && disabledWorlds.contains(worldName);
    }

    public List<RedstoneHotspot> getHotspots() {
        return lastHotspots;
    }

    public RedstoneActivity getActivity(Chunk chunk) {
        Map<Long, RedstoneActivity> world = activityByWorld.get(chunk.getWorld().getName());
        return world == null ? null : world.get(chunkKey(chunk.getX(), chunk.getZ()));
    }

    public RedstoneMode getMode() {
        return mode;
    }

    public int getTrackedChunkCount() {
        int total = 0;
        for (Map<Long, RedstoneActivity> world : activityByWorld.values()) {
            total += world.size();
        }
        return total;
    }

    public long getTotalBlockedActions() {
        long total = 0;
        for (Map<Long, RedstoneActivity> world : activityByWorld.values()) {
            for (RedstoneActivity activity : world.values()) {
                total += activity.getBlockedActions();
            }
        }
        return total;
    }

    public boolean isLimitingActive() {
        return pistonLimitEnabled || dispenserLimitEnabled;
    }
}
