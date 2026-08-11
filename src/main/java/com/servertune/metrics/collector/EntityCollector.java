package com.servertune.metrics.collector;

import com.servertune.ServerTunePlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class EntityCollector implements Listener {

    private final ServerTunePlugin plugin;
    private final Map<EntityType, AtomicInteger> entityCounts;
    private boolean initialized = false;

    public EntityCollector(ServerTunePlugin plugin) {
        this.plugin = plugin;
        this.entityCounts = new ConcurrentHashMap<>();
        initialize();
    }

    private void initialize() {
        if (initialized) {
            return;
        }

        // Register event listener
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Perform initial count (lightweight - just iterate current entities once)
        Bukkit.getScheduler().runTask(plugin, this::performInitialCount);

        initialized = true;
    }

    private void performInitialCount() {
        try {
            entityCounts.clear();
            Bukkit.getWorlds().forEach(world ->
                world.getEntities().forEach(entity ->
                    incrementEntity(entity.getType())
                )
            );
            plugin.getLogger().info("Entity tracking initialized");
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to perform initial entity count: " + e.getMessage());
        }
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        incrementEntity(event.getEntityType());
    }

    @EventHandler
    public void onEntityRemove(EntityRemoveEvent event) {
        // UNLOAD is skipped here because EntitiesUnloadEvent already decrements every entity in
        // the chunk. Counting both fired one decrement per entity per unload, which drove counts
        // to zero, dropped the map entry, and left the totals permanently low once the chunk was
        // reloaded and EntitiesLoadEvent counted it back up from nothing.
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD) {
            return;
        }
        decrementEntity(event.getEntityType());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        // When entities are loaded (e.g., chunk load), count them
        for (Entity entity : event.getEntities()) {
            incrementEntity(entity.getType());
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        // When entities are unloaded (e.g., chunk unload), remove them from count
        for (Entity entity : event.getEntities()) {
            decrementEntity(entity.getType());
        }
    }

    private void incrementEntity(EntityType type) {
        entityCounts.computeIfAbsent(type, k -> new AtomicInteger(0)).incrementAndGet();
    }

    private void decrementEntity(EntityType type) {
        AtomicInteger count = entityCounts.get(type);
        if (count != null) {
            int newValue = count.decrementAndGet();
            // Remove if count reaches 0 to keep map clean
            if (newValue <= 0) {
                entityCounts.remove(type);
            }
        }
    }

    public Map<EntityType, Integer> getEntityCounts() {
        Map<EntityType, Integer> snapshot = new HashMap<>();
        entityCounts.forEach((type, count) -> {
            int value = count.get();
            if (value > 0) {
                snapshot.put(type, value);
            }
        });
        return snapshot;
    }

    public int getTotalEntityCount() {
        return entityCounts.values().stream()
                .mapToInt(AtomicInteger::get)
                .sum();
    }

    /**
     * Unregisters this listener and drops the counts. Called from the one existing shutdown path
     * ({@code PluginLifecycle.shutdown} via {@code MetricsCollector.shutdown}); no separate
     * lifecycle mechanism is introduced. Without it, a reload-style disable left the handler on
     * Bukkit's list holding this instance and the plugin classloader alive.
     */
    public void shutdown() {
        HandlerList.unregisterAll(this);
        entityCounts.clear();
        initialized = false;
    }
}
