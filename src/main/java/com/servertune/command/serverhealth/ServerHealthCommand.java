package com.servertune.command.serverhealth;

import com.servertune.ServerTunePlugin;
import com.servertune.command.BaseCommand;
import com.servertune.command.DiagnosticsRenderer;
import com.servertune.diagnostics.DiagnosticSnapshot;
import com.servertune.diagnostics.DiagnosticsEngine;
import com.servertune.fallback.FallbackState;
import com.servertune.metrics.HealthSnapshot;
import io.papermc.paper.datapack.Datapack;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ServerHealthCommand extends BaseCommand implements CommandExecutor {

    private final ServerTunePlugin plugin;
    private final Map<String, BukkitTask> liveViewTasks = new ConcurrentHashMap<>();

    public ServerHealthCommand(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, args);
    }

    /**
     * The command body, reachable from both registration paths. The Brigadier tree calls this
     * directly; {@code onCommand} delegates to it unchanged. Permission checks stay here rather
     * than only on the tree, so a sender who reaches a subcommand by any route is checked the
     * same way.
     */
    public boolean execute(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "serverhealth.use")) {
            return true;
        }

        // Handle subcommands
        if (args.length > 0) {
            String subcommand = args[0].toLowerCase();
            return switch (subcommand) {
                case "version" -> handleVersion(sender);
                case "chunks" -> handleChunks(sender);
                case "entities" -> handleEntities(sender);
                case "plugins" -> handlePlugins(sender);
                case "datapacks" -> handleDatapacks(sender);
                case "diagnose" -> handleDiagnose(sender);
                case "hotspots" -> handleHotspots(sender);
                case "live" -> handleLive(sender, args);
                case "debug" -> handleDebug(sender);
                default -> {
                    sendError(sender, "§cUnknown subcommand. Available: chunks, entities, plugins, datapacks, diagnose, hotspots, live, debug");
                    yield true;
                }
            };
        }

        // Get cached snapshot
        HealthSnapshot snapshot = plugin.getSnapshotCache().getLatest();

        if (snapshot == null) {
            sendError(sender, "§cNo health data available yet. Please wait a moment...");
            return true;
        }

        // Display compact health overview
        sendRaw(sender, "§8§m                    §r §6§lSERVER HEALTH§r §8§m                    §r");
        sendRaw(sender, "");
        sendRaw(sender, "  §7TPS: §f" + formatDecimal(snapshot.getTps(), 2));
        sendRaw(sender, "  §7MSPT: §f" + formatDecimal(snapshot.getMspt(), 2) + " ms");
        sendRaw(sender, "  §7CPU: §f" + formatPercentage(snapshot.getCpuUsage()));
        sendRaw(sender, "  §7RAM: §f" + formatMemory(snapshot.getMemoryUsed()) + " §7/§f " + formatMemory(snapshot.getMemoryMax()) + " GB");

        String statusColor = getStatusColor(snapshot.getServerStatus());
        sendRaw(sender, "  §7Status: " + statusColor + snapshot.getServerStatus());

        // Show optimizer state
        if (plugin.getPerformanceGuard() != null) {
            FallbackState state = plugin.getPerformanceGuard().getCurrentState();
            String stateColor = getStateColor(state);
            sendRaw(sender, "  §7Optimizer: " + stateColor + state.name());
        }

        sendRaw(sender, "");

        // Show time since last update
        long timeSince = plugin.getSnapshotCache().getTimeSinceUpdate();
        sendRaw(sender, "§8Updated " + formatTimeSince(timeSince));
        sendRaw(sender, "§8§m                                                      §r");

        return true;
    }

    private boolean handleVersion(CommandSender sender) {
        sendMessage(sender, "§6ServerTune§r v" + plugin.getPluginMeta().getVersion());
        sendMessage(sender, "§7Running on Paper " + plugin.getServer().getVersion());
        return true;
    }

    private boolean handleChunks(CommandSender sender) {
        HealthSnapshot snapshot = plugin.getSnapshotCache().getLatest();
        if (snapshot == null) {
            sendError(sender, "§cNo health data available yet.");
            return true;
        }

        sendRaw(sender, "§8§m                    §r §6§lCHUNK INFO§r §8§m                    §r");
        sendRaw(sender, "");
        sendRaw(sender, "  §7Total Loaded: §f" + snapshot.getLoadedChunks());
        sendRaw(sender, "  §7Worlds: §f" + snapshot.getLoadedWorlds());
        sendRaw(sender, "");

        // Per-world breakdown, read from the tracker. world.getLoadedChunks() would build an
        // array of every loaded chunk per world just so this could read its length.
        sendRaw(sender, "§e§lPer-World Breakdown:");
        Bukkit.getWorlds().forEach(world -> {
            int chunks = plugin.getChunkTracker().getLoadedChunkCount(world.getName());
            sendRaw(sender, "  §7" + world.getName() + ": §f" + chunks + " chunks");
        });

        sendRaw(sender, "");
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    private boolean handleEntities(CommandSender sender) {
        HealthSnapshot snapshot = plugin.getSnapshotCache().getLatest();
        if (snapshot == null) {
            sendError(sender, "§cNo health data available yet.");
            return true;
        }

        sendRaw(sender, "§8§m                    §r §6§lENTITY INFO§r §8§m                    §r");
        sendRaw(sender, "");
        sendRaw(sender, "  §7Total Entities: §f" + snapshot.getTotalEntities());
        sendRaw(sender, "");

        // Top entity types
        sendRaw(sender, "§e§lTop Entity Types:");
        Map<EntityType, Integer> entityCounts = snapshot.getEntityCounts();
        entityCounts.entrySet().stream()
                .sorted(Map.Entry.<EntityType, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> {
                    sendRaw(sender, "  §7" + formatEntityName(entry.getKey()) + ": §f" + entry.getValue());
                });

        sendRaw(sender, "");
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    private boolean handlePlugins(CommandSender sender) {
        sendRaw(sender, "§8§m                    §r §6§lPLUGIN INFO§r §8§m                    §r");
        sendRaw(sender, "");

        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        sendRaw(sender, "  §7Loaded Plugins: §f" + plugins.length);
        sendRaw(sender, "");

        sendRaw(sender, "§e§lEnabled Plugins:");
        for (Plugin p : plugins) {
            if (p.isEnabled()) {
                sendRaw(sender, "  §a✓ §7" + p.getName() + " §8v" + p.getPluginMeta().getVersion());
            }
        }

        sendRaw(sender, "");
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    /**
     * Reads the already-loaded pack list. {@code refreshPacks()} would rescan the datapack
     * directory from disk on the main thread, so this reports what the server currently has
     * rather than going looking for changes.
     */
    private boolean handleDatapacks(CommandSender sender) {
        Collection<Datapack> packs = Bukkit.getDatapackManager().getPacks();

        sendRaw(sender, "§8§m                    §r §6§lDATAPACK INFO§r §8§m                    §r");
        sendRaw(sender, "");

        long enabled = packs.stream().filter(Datapack::isEnabled).count();
        sendRaw(sender, "  §7Total: §f" + packs.size() + " §8(§a" + enabled + " enabled§8)");
        sendRaw(sender, "");

        if (packs.isEmpty()) {
            sendRaw(sender, "  §7No datapacks found.");
            sendRaw(sender, "");
            sendRaw(sender, "§8§m                                                      §r");
            return true;
        }

        sendRaw(sender, "§e§lDatapacks:");
        for (Datapack pack : packs) {
            String marker = pack.isEnabled() ? "§a✓" : "§8✗";
            String required = pack.isRequired() ? " §8(required)" : "";
            String compatibility = switch (pack.getCompatibility()) {
                case COMPATIBLE -> "";
                case TOO_OLD -> " §cout of date";
                case TOO_NEW -> " §ctoo new";
            };
            sendRaw(sender, "  " + marker + " §7" + pack.getName() + required + compatibility);
        }

        sendRaw(sender, "");
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    /**
     * Diagnoses from the last periodic sample. Runs no scan of its own: the analysis happens
     * off-thread against an immutable snapshot and the age of that snapshot is printed, so a
     * diagnose command can never be the tick spike it is meant to help find.
     */
    private boolean handleDiagnose(CommandSender sender) {
        if (!checkPermission(sender, "serverhealth.diagnose")) {
            return true;
        }

        DiagnosticsEngine engine = plugin.getDiagnosticsEngine();
        if (engine == null || !engine.isEnabled()) {
            sendError(sender, "§cDiagnostics are disabled in configuration.");
            return true;
        }

        if (engine.getLatestSnapshot() == null) {
            sendError(sender, "§cNo diagnostic sample taken yet. The first one runs within "
                    + (engine.getSampleIntervalTicks() / 20) + "s of startup.");
            return true;
        }

        engine.analyzeAsync(report -> {
            if (report.isEmpty()) {
                sendError(sender, "§cNo diagnostic sample available.");
                return;
            }
            DiagnosticsRenderer.renderDiagnosis(sender, report);
        });

        return true;
    }

    /** Hotspots on their own, for when the full diagnosis is more than the reader wants. */
    private boolean handleHotspots(CommandSender sender) {
        if (!checkPermission(sender, "serverhealth.diagnose")) {
            return true;
        }

        DiagnosticsEngine engine = plugin.getDiagnosticsEngine();
        if (engine == null || !engine.isEnabled()) {
            sendError(sender, "§cDiagnostics are disabled in configuration.");
            return true;
        }

        DiagnosticSnapshot snapshot = engine.getLatestSnapshot();
        if (snapshot == null) {
            sendError(sender, "§cNo diagnostic sample taken yet.");
            return true;
        }

        sendRaw(sender, "§8§m                    §r §6§lHOTSPOTS§r §8§m                    §r");
        sendRaw(sender, "");
        DiagnosticsRenderer.renderHotspots(sender, snapshot.getHotspots(),
                snapshot.isPerChunkEntityCountsMeasured());
        sendRaw(sender, "§8Data sampled "
                + (snapshot.getAgeMillis(System.currentTimeMillis()) / 1000L) + " seconds ago.");
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    private boolean handleLive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendError(sender, "§cThis command can only be used by players.");
            return true;
        }

        // Check for stop subcommand
        if (args.length > 1 && args[1].equalsIgnoreCase("stop")) {
            return stopLiveView(player);
        }

        // Start live view
        if (liveViewTasks.containsKey(player.getName())) {
            sendError(sender, "§cLive view already active. Use /serverhealth live stop to stop.");
            return true;
        }

        if (!plugin.getConfig().getBoolean("server-health.live.enabled", true)) {
            sendError(sender, "§cLive view is disabled in configuration.");
            return true;
        }

        int updateInterval = plugin.getConfig().getInt("server-health.live.update-interval", 40);
        int maxDurationSeconds = plugin.getConfig().getInt("server-health.live.max-duration", 300);

        if (maxDurationSeconds > 0) {
            sendMessage(sender, "§aLive health view started (stops after " + maxDurationSeconds
                    + "s). Use §f/serverhealth live stop§a to stop.");
        } else {
            sendMessage(sender, "§aLive health view started. Use §f/serverhealth live stop§a to stop.");
        }

        // max-duration was documented in config.yml but never enforced, so a live view ran
        // forever - a repeating task per player that only ever stopped on logout.
        long expiryTick = maxDurationSeconds > 0
                ? Bukkit.getCurrentTick() + (maxDurationSeconds * 20L)
                : Long.MAX_VALUE;

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                stopLiveView(player);
                return;
            }

            if (Bukkit.getCurrentTick() >= expiryTick) {
                sendMessage(player, "§7Live view stopped after " + maxDurationSeconds + "s.");
                stopLiveView(player);
                return;
            }

            HealthSnapshot snapshot = plugin.getSnapshotCache().getLatest();
            if (snapshot == null) return;

            // Send compact update
            player.sendMessage("§8§m        §r §6LIVE§r §8§m        §r");
            player.sendMessage("§7TPS: §f" + formatDecimal(snapshot.getTps(), 2) +
                    " §8| §7MSPT: §f" + formatDecimal(snapshot.getMspt(), 2) + "ms");
            player.sendMessage("§7CPU: §f" + formatPercentage(snapshot.getCpuUsage()) +
                    " §8| §7RAM: §f" + formatMemory(snapshot.getMemoryUsed()) + "GB");

        }, 20L, updateInterval);

        liveViewTasks.put(player.getName(), task);
        return true;
    }

    private boolean stopLiveView(Player player) {
        BukkitTask task = liveViewTasks.remove(player.getName());
        if (task != null) {
            task.cancel();
            sendMessage(player, "§aLive view stopped.");
        } else {
            sendError(player, "§cNo active live view.");
        }
        return true;
    }

    private boolean handleDebug(CommandSender sender) {
        if (!checkPermission(sender, "serverhealth.admin")) {
            return true;
        }

        sendRaw(sender, "§8§m                    §r §6§lDEBUG INFO§r §8§m                    §r");
        sendRaw(sender, "");

        // Everything below reads state the self-monitor already recorded. Nothing here starts a
        // timer, collects a snapshot or touches a world: running an expensive measurement to
        // display how expensive things are would be self-defeating.
        sendRaw(sender, "§e§lSelf Monitor:");
        Map<String, com.servertune.selfmonitor.SubsystemMetrics> metrics =
                plugin.getSelfMonitor().getAllMetrics();

        com.servertune.selfmonitor.BudgetWarningPolicy policy =
                plugin.getSelfMonitor().getWarningPolicy();

        // Stated up front, because this view is the whole reason console logging can default to
        // off: a reader who sees no warnings in the log needs to know whether that means the
        // server is fine or the log is quiet. The measurements below answer that either way.
        sendRaw(sender, "  §7Console logging: " + switch (policy.getLoggingSource()) {
            case CONFIG -> "§aON §8(config.yml)";
            case RUNTIME -> "§aON §8(runtime override, not saved)";
            case RUNTIME_OFF -> "§cOFF §8(runtime override, not saved)";
            case OFF -> "§cOFF";
        });
        sendRaw(sender, "  §8Monitoring runs regardless; these figures are recorded either way.");
        sendRaw(sender, "");

        // The dormancy check for the on-demand plugin diagnostic. Anything other than "dormant"
        // while no session is running is a leak, and this is where an operator sees it without
        // reading a heap dump.
        if (plugin.getPluginDiagnostics() != null) {
            for (String line : plugin.getPluginDiagnostics().debugLines()) {
                sendRaw(sender, "  §7" + line);
            }
            sendRaw(sender, "");
        }

        if (metrics.isEmpty()) {
            sendRaw(sender, "  §7No performance data collected yet.");
        } else {
            com.servertune.guard.BudgetViolationTracker tracker =
                    plugin.getSelfMonitor().getBudgetTracker();

            metrics.forEach((name, metric) -> {
                double budget = tracker.budgetFor(name);
                com.servertune.selfmonitor.BudgetWarningPolicy.Status status = policy.statusOf(name);
                boolean over = status != null && status.overBudget();

                sendRaw(sender, "  §f" + name + "§7:");
                sendRaw(sender, String.format("    §7current: §f%.2f ms §8(avg %.2f, max %.2f)",
                        metric.getRecentExecutionMs(),
                        metric.getAverageExecutionMs(),
                        metric.getMaxExecutionMs()));
                sendRaw(sender, String.format("    §7budget: §f%.2f ms", budget));
                sendRaw(sender, "    §7status: " + (over ? "§cOVER BUDGET" : "§aOK"));

                if (status != null && status.hasWarned()) {
                    sendRaw(sender, "    §7last warning: §f" + formatClockTime(status.lastWarnAt()));
                    if (status.suppressedSinceWarn() > 0) {
                        sendRaw(sender, "    §7suppressed since: §f"
                                + status.suppressedSinceWarn() + " overrun(s)");
                    }
                } else if (status != null && status.totalSuppressed() > 0) {
                    // Over budget while the console was silent: the count is the only trace,
                    // so it has to be shown here or the overruns are invisible.
                    sendRaw(sender, "    §7unlogged overruns: §f"
                            + status.totalSuppressed());
                }
            });

            double total = plugin.getSelfMonitor().getTotalAverageExecutionTime();
            sendRaw(sender, "");
            sendRaw(sender, "  §7Total Average: §f" + formatDecimal(total, 2) + " ms");
        }

        sendRaw(sender, "");
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    /** Wall-clock HH:mm:ss for a millisecond timestamp, in the server's local zone. */
    private String formatClockTime(long epochMillis) {
        return java.time.LocalTime
                .ofInstant(java.time.Instant.ofEpochMilli(epochMillis),
                        java.time.ZoneId.systemDefault())
                .withNano(0)
                .toString();
    }

    private String formatEntityName(EntityType type) {
        String name = type.name().toLowerCase().replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    private String getStateColor(FallbackState state) {
        return switch (state) {
            case NORMAL -> "§a";
            case WARNING -> "§e";
            case CRITICAL -> "§6";
            case FALLBACK -> "§c";
            case RECOVERING -> "§b";
        };
    }
}
