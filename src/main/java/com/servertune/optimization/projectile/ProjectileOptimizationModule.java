package com.servertune.optimization.projectile;

import com.servertune.ServerTunePlugin;
import com.servertune.core.ModuleState;
import com.servertune.core.OptimizationModule;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

public class ProjectileOptimizationModule implements OptimizationModule {

    private final ServerTunePlugin plugin;
    private ModuleState state = ModuleState.DISABLED;

    // Configuration
    private int arrowLifetimeTicks;
    private int tridentLifetimeTicks;
    private int otherProjectileLifetimeTicks;
    private final Set<String> disabledWorlds = new HashSet<>();

    public ProjectileOptimizationModule(ServerTunePlugin plugin) {
        this.plugin = plugin;
        loadConfiguration();
    }

    private void loadConfiguration() {
        String base = "optimization.modules.projectile.";
        arrowLifetimeTicks = plugin.getConfig().getInt(base + "arrow-lifetime-ticks", 1200);
        tridentLifetimeTicks = plugin.getConfig().getInt(base + "trident-lifetime-ticks", 1200);
        otherProjectileLifetimeTicks = plugin.getConfig().getInt(base + "other-lifetime-ticks", 600);

        disabledWorlds.clear();
        disabledWorlds.addAll(plugin.getConfig().getStringList(base + "disabled-worlds"));
    }

    @Override
    public String getName() {
        return "projectile-optimization";
    }

    @Override
    public ModuleState getState() {
        return state;
    }

    @Override
    public void enable() {
        loadConfiguration();
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[ProjectileOptimization] Enabled");
    }

    @Override
    public void disable() {
        state = ModuleState.DISABLED;
        plugin.getLogger().info("[ProjectileOptimization] Disabled");
    }

    @Override
    public void suspend() {
        state = ModuleState.SUSPENDED;
        plugin.getLogger().info("[ProjectileOptimization] Suspended");
    }

    @Override
    public void resume() {
        state = ModuleState.ENABLED;
        plugin.getLogger().info("[ProjectileOptimization] Resumed");
    }

    @Override
    public void optimize() {
        if (state != ModuleState.ENABLED) {
            return;
        }

        try {
            int removedCount = 0;

            for (World world : Bukkit.getWorlds()) {
                if (disabledWorlds.contains(world.getName())) {
                    continue;
                }

                // Iterated directly: nothing here mutates the collection, so the defensive
                // ArrayList copy this used to make was pure allocation.
                for (Projectile projectile : world.getEntitiesByClass(Projectile.class)) {
                    if (!projectile.isValid()) {
                        continue;
                    }

                    int lifetime = getLifetimeLimit(projectile);
                    if (lifetime > 0 && projectile.getTicksLived() > lifetime) {
                        projectile.remove();
                        removedCount++;
                    }
                }
            }

            if (plugin.getConfigManager().isDebugEnabled() && removedCount > 0) {
                plugin.getLogger().info(String.format(
                    "[ProjectileOptimization] Removed %d old projectiles",
                    removedCount
                ));
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[ProjectileOptimization] Error during optimization", e);
        }
    }

    private int getLifetimeLimit(Projectile projectile) {
        if (projectile instanceof Arrow) {
            return arrowLifetimeTicks;
        } else if (projectile instanceof Trident) {
            return tridentLifetimeTicks;
        } else {
            return otherProjectileLifetimeTicks;
        }
    }
}
