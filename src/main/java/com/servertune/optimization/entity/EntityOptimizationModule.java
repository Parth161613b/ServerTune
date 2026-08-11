package com.servertune.optimization.entity;

import com.servertune.ServerTunePlugin;
import com.servertune.core.ModuleState;
import com.servertune.core.OptimizationModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Global and per-chunk limits on living entities.
 *
 * <p><b>Gameplay impact is HIGH</b> - this module removes mobs. It is disabled by default and
 * every protection in {@link #isProtected(LivingEntity)} exists to keep it from eating
 * something a player built or bred.
 */
public class EntityOptimizationModule implements OptimizationModule {

    private final ServerTunePlugin plugin;
    private ModuleState state = ModuleState.DISABLED;

    // Configuration
    private int hostileMobLimit;
    private int passiveMobLimit;
    private int waterMobLimit;
    private int villagerLimit;
    private boolean perChunkLimits;
    private int hostilePerChunk;
    private int passivePerChunk;
    private int waterPerChunk;
    private final Set<String> protectedTypes = new HashSet<>();
    private final Set<String> disabledWorlds = new HashSet<>();

    /** Reused for coordinate reads so the chunk pass allocates no Location per entity. */
    private final Location scratch = new Location(null, 0, 0, 0);

    public EntityOptimizationModule(ServerTunePlugin plugin) {
        this.plugin = plugin;
        loadConfiguration();
    }

    private void loadConfiguration() {
        String base = "optimization.modules.entity.";
        hostileMobLimit = plugin.getConfig().getInt(base + "limits.hostile-global", -1);
        passiveMobLimit = plugin.getConfig().getInt(base + "limits.passive-global", -1);
        waterMobLimit = plugin.getConfig().getInt(base + "limits.water-global", -1);
        villagerLimit = plugin.getConfig().getInt(base + "limits.villager-global", -1);

        perChunkLimits = plugin.getConfig().getBoolean(base + "per-chunk.enabled", true);
        hostilePerChunk = plugin.getConfig().getInt(base + "per-chunk.hostile", 20);
        passivePerChunk = plugin.getConfig().getInt(base + "per-chunk.passive", 15);
        waterPerChunk = plugin.getConfig().getInt(base + "per-chunk.water", 10);

        protectedTypes.clear();
        protectedTypes.addAll(plugin.getConfig().getStringList(base + "protected-types"));

        disabledWorlds.clear();
        disabledWorlds.addAll(plugin.getConfig().getStringList(base + "disabled-worlds"));
    }

    @Override
    public String getName() {
        return "entity-optimization";
    }

    @Override
    public ModuleState getState() {
        return state;
    }

    @Override
    public void enable() {
        loadConfiguration();
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[EntityOptimization] Enabled");
    }

    @Override
    public void disable() {
        state = ModuleState.DISABLED;
        plugin.getLogger().info("[EntityOptimization] Disabled");
    }

    @Override
    public void suspend() {
        state = ModuleState.SUSPENDED;
        plugin.getLogger().info("[EntityOptimization] Suspended");
    }

    @Override
    public void resume() {
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[EntityOptimization] Resumed");
    }

    @Override
    public void optimize() {
        if (state != ModuleState.ENABLED) {
            return;
        }

        boolean globalLimitsActive = hostileMobLimit > 0 || passiveMobLimit > 0
                || waterMobLimit > 0 || villagerLimit > 0;
        boolean chunkLimitsActive = perChunkLimits
                && (hostilePerChunk > 0 || passivePerChunk > 0 || waterPerChunk > 0);

        // With every limit off (the shipped default: all -1) there is nothing this module
        // could remove, so it must not pay for a world entity scan to discover that.
        if (!globalLimitsActive && !chunkLimitsActive) {
            return;
        }

        try {
            int removedCount = 0;

            for (World world : Bukkit.getWorlds()) {
                if (!isWorldEnabled(world)) {
                    continue;
                }

                List<LivingEntity> entities =
                        new ArrayList<>(world.getEntitiesByClass(LivingEntity.class));

                if (globalLimitsActive) {
                    removedCount += applyGlobalLimits(entities);
                }

                if (chunkLimitsActive) {
                    removedCount += applyChunkLimits(entities);
                }
            }

            if (plugin.getConfigManager().isDebugEnabled() && removedCount > 0) {
                plugin.getLogger().info(String.format(
                    "[EntityOptimization] Removed %d entities",
                    removedCount
                ));
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[EntityOptimization] Error during optimization", e);
        }
    }

    private int applyGlobalLimits(List<LivingEntity> entities) {
        int removedCount = 0;

        // Categorize entities
        List<LivingEntity> hostile = new ArrayList<>();
        List<LivingEntity> passive = new ArrayList<>();
        List<LivingEntity> water = new ArrayList<>();
        List<LivingEntity> villagers = new ArrayList<>();

        for (LivingEntity entity : entities) {
            if (isProtected(entity)) {
                continue;
            }

            if (entity instanceof Villager) {
                villagers.add(entity);
            } else if (entity instanceof Monster) {
                hostile.add(entity);
            } else if (entity instanceof WaterMob) {
                water.add(entity);
            } else if (entity instanceof Animals) {
                passive.add(entity);
            }
        }

        // Apply limits
        if (hostileMobLimit > 0 && hostile.size() > hostileMobLimit) {
            removedCount += removeExcess(hostile, hostile.size() - hostileMobLimit);
        }

        if (passiveMobLimit > 0 && passive.size() > passiveMobLimit) {
            removedCount += removeExcess(passive, passive.size() - passiveMobLimit);
        }

        if (waterMobLimit > 0 && water.size() > waterMobLimit) {
            removedCount += removeExcess(water, water.size() - waterMobLimit);
        }

        if (villagerLimit > 0 && villagers.size() > villagerLimit) {
            removedCount += removeExcess(villagers, villagers.size() - villagerLimit);
        }

        return removedCount;
    }

    private int applyChunkLimits(List<LivingEntity> entities) {
        Map<Long, List<LivingEntity>> chunkHostile = new HashMap<>();
        Map<Long, List<LivingEntity>> chunkPassive = new HashMap<>();
        Map<Long, List<LivingEntity>> chunkWater = new HashMap<>();

        // Group entities by chunk and type
        for (LivingEntity entity : entities) {
            if (isProtected(entity)) {
                continue;
            }

            // Coordinate arithmetic, not Location#getChunk(): an entity's chunk is just its
            // block coordinates shifted, and getChunk() resolves a chunk object per entity.
            entity.getLocation(scratch);
            long chunkKey = getChunkKey(scratch.getBlockX() >> 4, scratch.getBlockZ() >> 4);

            if (entity instanceof Monster) {
                chunkHostile.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entity);
            } else if (entity instanceof WaterMob) {
                chunkWater.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entity);
            } else if (entity instanceof Animals) {
                chunkPassive.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(entity);
            }
        }

        int removedCount = 0;

        // A per-chunk limit of 0 or below means "no limit", never "remove them all".
        removedCount += enforcePerChunk(chunkHostile, hostilePerChunk);
        removedCount += enforcePerChunk(chunkPassive, passivePerChunk);
        removedCount += enforcePerChunk(chunkWater, waterPerChunk);

        return removedCount;
    }

    private int enforcePerChunk(Map<Long, List<LivingEntity>> byChunk, int limit) {
        if (limit <= 0) {
            return 0;
        }

        int removedCount = 0;
        for (List<LivingEntity> list : byChunk.values()) {
            if (list.size() > limit) {
                removedCount += removeExcess(list, list.size() - limit);
            }
        }
        return removedCount;
    }

    private int removeExcess(List<LivingEntity> entities, int count) {
        // Sort by age (oldest first) - prefer removing older entities
        entities.sort((a, b) -> Integer.compare(b.getTicksLived(), a.getTicksLived()));

        int removedCount = 0;
        for (LivingEntity entity : entities) {
            if (removedCount >= count) {
                break;
            }

            if (!isProtected(entity)) {
                entity.remove();
                removedCount++;
            }
        }

        return removedCount;
    }

    /**
     * Whether this entity must never be removed by a limit.
     *
     * <p>Deliberately does <b>not</b> test {@code Entity#isPersistent()}. That flag means
     * "saved to disk", and it is {@code true} for most entities, so testing it here protected
     * nearly everything and made the whole module a silent no-op. The flag that actually means
     * "vanilla would never despawn this" is {@code LivingEntity#getRemoveWhenFarAway() == false},
     * which covers name-tagged, bred, and persistence-required mobs.
     */
    private boolean isProtected(LivingEntity entity) {
        if (entity instanceof Player) {
            return true;
        }

        if (entity.customName() != null) {
            return true;
        }

        if (entity instanceof Tameable tameable && tameable.isTamed()) {
            return true;
        }

        if (entity.isLeashed()) {
            return true;
        }

        // A mob vanilla itself refuses to despawn stays put.
        if (!entity.getRemoveWhenFarAway()) {
            return true;
        }

        return !protectedTypes.isEmpty() && protectedTypes.contains(entity.getType().name());
    }

    private boolean isWorldEnabled(World world) {
        return !disabledWorlds.contains(world.getName());
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
