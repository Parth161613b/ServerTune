package com.servertune.core;

import com.servertune.ServerTunePlugin;
import com.servertune.optimization.chunk.ChunkOptimizationModule;
import com.servertune.optimization.entity.EntityOptimizationModule;
import com.servertune.optimization.hopper.HopperOptimizationModule;
import com.servertune.optimization.item.ItemOptimizationModule;
import com.servertune.optimization.projectile.ProjectileOptimizationModule;
import com.servertune.optimization.redstone.RedstoneOptimizationModule;
import com.servertune.optimization.xp.XPOptimizationModule;

import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ModuleManager {

    /**
     * Module name to its {@code enabled} config key. Startup and reload both read this, so a
     * module can never be enabled by one path and missed by the other.
     */
    private static final Map<String, String> MODULE_CONFIG_KEYS = Map.of(
            "item-optimization", "optimization.modules.item.enabled",
            "xp-optimization", "optimization.modules.xp.enabled",
            "projectile-optimization", "optimization.modules.projectile.enabled",
            "entity-optimization", "optimization.modules.entity.enabled",
            "chunk-optimization", "optimization.modules.chunk.enabled",
            "hopper-optimization", "optimization.modules.hopper.enabled",
            "redstone-optimization", "optimization.modules.redstone.enabled");

    private final ServerTunePlugin plugin;
    private final Map<String, OptimizationModule> modules;
    private boolean modulesInitialized = false;

    public ModuleManager(ServerTunePlugin plugin) {
        this.plugin = plugin;
        this.modules = new ConcurrentHashMap<>();
    }

    public void registerModule(OptimizationModule module) {
        String name = module.getName();
        if (modules.containsKey(name)) {
            plugin.getLogger().warning("Module " + name + " is already registered");
            return;
        }

        modules.put(name, module);
        plugin.getLogger().info("Registered module: " + name);
    }

    public void initializeModules() {
        if (modulesInitialized) {
            return;
        }

        // Entity-facing modules
        registerModule(new ItemOptimizationModule(plugin));
        registerModule(new XPOptimizationModule(plugin));
        registerModule(new ProjectileOptimizationModule(plugin));
        registerModule(new EntityOptimizationModule(plugin));

        // Chunk lifecycle
        registerModule(new ChunkOptimizationModule(plugin));

        // Block entities
        registerModule(new HopperOptimizationModule(plugin));

        // Redstone
        registerModule(new RedstoneOptimizationModule(plugin));

        // Enable modules based on configuration
        enableConfiguredModules();

        plugin.getLogger().info("Module initialization complete (" + modules.size() + " modules registered)");
        modulesInitialized = true;
    }

    /**
     * Re-applies configuration to the modules already registered.
     *
     * <p>This is what a reload needs, and it is deliberately not {@link #initializeModules()}.
     * That method is idempotent on purpose: re-running it would construct a second
     * {@code HopperOptimizationModule} and {@code RedstoneOptimizationModule}, each registering
     * its own listener, so every hopper transfer and redstone event would then be handled twice.
     *
     * <p>Each module re-reads its own config inside {@code enable()}, so enabling an
     * already-enabled module is how new settings take effect. A module the config now turns off
     * is disabled here, and one suspended by the performance guard is returned to whatever the
     * config asks for.
     */
    public void reloadModules() {
        for (Map.Entry<String, String> entry : MODULE_CONFIG_KEYS.entrySet()) {
            String name = entry.getKey();
            if (modules.get(name) == null) {
                continue;
            }

            if (plugin.getConfig().getBoolean(entry.getValue(), false)) {
                enableModule(name);
            } else {
                disableModule(name);
            }
        }

        plugin.getLogger().info("Module configuration reloaded (" + modules.size() + " modules registered)");
    }

    private void enableConfiguredModules() {
        for (Map.Entry<String, String> entry : MODULE_CONFIG_KEYS.entrySet()) {
            if (plugin.getConfig().getBoolean(entry.getValue(), false)) {
                enableModule(entry.getKey());
            }
        }
    }

    public void enableModule(String name) {
        OptimizationModule module = modules.get(name);
        if (module == null) {
            plugin.getLogger().warning("Module not found: " + name);
            return;
        }

        try {
            module.enable();
            plugin.getLogger().info("Enabled module: " + name);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to enable module: " + name, e);
        }
    }

    public void disableModule(String name) {
        OptimizationModule module = modules.get(name);
        if (module == null) {
            plugin.getLogger().warning("Module not found: " + name);
            return;
        }

        try {
            module.disable();
            plugin.getLogger().info("Disabled module: " + name);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to disable module: " + name, e);
        }
    }

    public void suspendAllModules() {
        for (OptimizationModule module : modules.values()) {
            // Only suspend running modules, so disabled modules are not resumed later
            if (module.getState() != ModuleState.ENABLED) {
                continue;
            }

            try {
                module.suspend();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to suspend module: " + module.getName(), e);
            }
        }
    }

    public void resumeAllModules() {
        for (OptimizationModule module : modules.values()) {
            // Only resume what we suspended; never turn on a disabled module
            if (module.getState() != ModuleState.SUSPENDED) {
                continue;
            }

            try {
                module.resume();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to resume module: " + module.getName(), e);
            }
        }
    }

    public void shutdown() {
        for (OptimizationModule module : modules.values()) {
            try {
                module.disable();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error disabling module: " + module.getName(), e);
            }
        }
        modules.clear();
    }

    public OptimizationModule getModule(String name) {
        return modules.get(name);
    }

    public Map<String, OptimizationModule> getAllModules() {
        return Map.copyOf(modules);
    }

    public int getModuleCount() {
        return modules.size();
    }
}
