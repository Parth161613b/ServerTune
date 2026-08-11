package com.servertune.scheduler;

import com.servertune.ServerTunePlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class OptimizerScheduler {

    /** Task name of the health monitor, which the guard reschedules but never cancels. */
    public static final String HEALTH_MONITOR_TASK = "health-monitor";

    private final ServerTunePlugin plugin;
    private final Map<String, BukkitTask> tasks;
    private boolean started = false;

    /**
     * Every optimization-module task the config asks for, declared once so the performance
     * guard can cancel them all in fallback and put the same set back on recovery.
     */
    private record ModuleTask(String taskName, String moduleName, String enabledPath,
                              String intervalPath, int defaultInterval) {
    }

    private static final List<ModuleTask> MODULE_TASKS = List.of(
            new ModuleTask("item-optimization", "item-optimization",
                    "optimization.modules.item.enabled",
                    "performance.intervals.modules.item-merge", 200),
            new ModuleTask("xp-optimization", "xp-optimization",
                    "optimization.modules.xp.enabled",
                    "performance.intervals.modules.item-merge", 200),
            new ModuleTask("projectile-optimization", "projectile-optimization",
                    "optimization.modules.projectile.enabled",
                    "performance.intervals.modules.entity-cleanup", 400),
            new ModuleTask("entity-optimization", "entity-optimization",
                    "optimization.modules.entity.enabled",
                    "performance.intervals.modules.entity-cleanup", 400),
            new ModuleTask("chunk-optimization", "chunk-optimization",
                    "optimization.modules.chunk.enabled",
                    "performance.intervals.modules.chunk-unload", 600),
            new ModuleTask("hopper-optimization", "hopper-optimization",
                    "optimization.modules.hopper.enabled",
                    "performance.intervals.modules.hopper", 400),
            new ModuleTask("redstone-optimization", "redstone-optimization",
                    "optimization.modules.redstone.enabled",
                    "performance.intervals.modules.redstone", 400)
    );

    public OptimizerScheduler(ServerTunePlugin plugin) {
        this.plugin = plugin;
        this.tasks = new ConcurrentHashMap<>();
    }

    public void start() {
        if (started) {
            plugin.getLogger().warning("Scheduler already started");
            return;
        }

        try {
            int healthInterval = plugin.getConfigManager().getHealthInterval();
            scheduleHealthMonitor(healthInterval);

            scheduleOptimizationModules();

            started = true;
            plugin.getLogger().info("Scheduler started successfully");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start scheduler", e);
        }
    }

    private void scheduleHealthMonitor(int interval) {
        scheduleTask(HEALTH_MONITOR_TASK, () -> plugin.getHealthMonitor().update(),
                interval, interval);
    }

    /**
     * Changes the health-monitor period at runtime, used by the adaptive monitoring in the
     * performance guard. The monitor is cancelled and re-created because Bukkit tasks have
     * no mutable period.
     */
    public void rescheduleHealthMonitor(int interval) {
        if (interval <= 0) {
            return;
        }

        cancelTask(HEALTH_MONITOR_TASK);
        scheduleHealthMonitor(interval);
    }

    private void scheduleOptimizationModules() {
        for (ModuleTask spec : MODULE_TASKS) {
            scheduleModuleTask(spec);
        }
    }

    private void scheduleModuleTask(ModuleTask spec) {
        if (!plugin.getConfig().getBoolean(spec.enabledPath(), false)) {
            return;
        }

        int interval = plugin.getConfig().getInt(spec.intervalPath(), spec.defaultInterval());
        scheduleTask(spec.taskName(), () -> {
            var module = plugin.getModuleManager().getModule(spec.moduleName());
            if (module == null) {
                return;
            }

            // Timed under the module's own name so self-monitoring can hold it to a budget
            // and, if it consistently overruns, suspend that module alone.
            var timer = plugin.getSelfMonitor().startTimer(spec.moduleName());
            try {
                module.optimize();
            } finally {
                timer.stop();
            }
        }, interval, interval);
    }

    /**
     * Cancels every optimization-module task, leaving the health monitor running so the
     * guard can still detect recovery.
     *
     * @return how many tasks were cancelled
     */
    public int cancelModuleTasks() {
        int cancelled = 0;

        for (ModuleTask spec : MODULE_TASKS) {
            if (tasks.containsKey(spec.taskName())) {
                cancelTask(spec.taskName());
                cancelled++;
            }
        }

        return cancelled;
    }

    /**
     * Re-schedules the module tasks the config still asks for. Safe to call when some are
     * already running; those are skipped.
     *
     * @return how many tasks were started
     */
    public int restartModuleTasks() {
        int restarted = 0;

        for (ModuleTask spec : MODULE_TASKS) {
            if (tasks.containsKey(spec.taskName())) {
                continue;
            }
            if (!plugin.getConfig().getBoolean(spec.enabledPath(), false)) {
                continue;
            }

            scheduleModuleTask(spec);
            if (tasks.containsKey(spec.taskName())) {
                restarted++;
            }
        }

        return restarted;
    }

    public void stop() {
        if (!started) {
            return;
        }

        try {
            for (Map.Entry<String, BukkitTask> entry : tasks.entrySet()) {
                try {
                    entry.getValue().cancel();
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to cancel task: " + entry.getKey());
                }
            }

            tasks.clear();
            started = false;
            plugin.getLogger().info("Scheduler stopped successfully");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error stopping scheduler", e);
        }
    }

    public void scheduleTask(String name, Runnable task, long delay, long period) {
        if (tasks.containsKey(name)) {
            plugin.getLogger().warning("Task " + name + " is already scheduled");
            return;
        }

        try {
            BukkitTask bukkitTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    () -> {
                        try {
                            task.run();
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.SEVERE, "Error executing task: " + name, e);
                        }
                    },
                    delay,
                    period
            );

            tasks.put(name, bukkitTask);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to schedule task: " + name, e);
        }
    }

    public void cancelTask(String name) {
        BukkitTask task = tasks.remove(name);
        if (task != null) {
            try {
                task.cancel();
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to cancel task: " + name);
            }
        }
    }

    public boolean isTaskScheduled(String name) {
        return tasks.containsKey(name);
    }

    public List<String> getScheduledTaskNames() {
        return new ArrayList<>(tasks.keySet());
    }

    public boolean isStarted() {
        return started;
    }
}
