package com.servertune.command.optimizer;

import com.servertune.ServerTunePlugin;
import com.servertune.command.BaseCommand;
import com.servertune.command.DiagnosticsRenderer;
import com.servertune.config.PerformanceProfile;
import com.servertune.core.ModuleState;
import com.servertune.core.OptimizationModule;
import com.servertune.diagnostics.DiagnosticsEngine;
import com.servertune.metrics.HealthSnapshot;
import com.servertune.pluginscan.PluginDiagnosticsService;
import com.servertune.pluginscan.PluginScanFormatter;
import com.servertune.pluginscan.PluginScanSettings;
import com.servertune.selfmonitor.BudgetWarningPolicy;
import com.servertune.selfmonitor.SelfMonitorLogging;
import com.servertune.selfmonitor.SubsystemMetrics;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;

import java.util.Map;

public class OptimizerCommand extends BaseCommand implements CommandExecutor {

    private final ServerTunePlugin plugin;

    public OptimizerCommand(ServerTunePlugin plugin) {
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
        if (!checkPermission(sender, "optimizer.use")) {
            return true;
        }

        if (args.length == 0) {
            return handleStatus(sender);
        }

        String subcommand = args[0].toLowerCase();
        return switch (subcommand) {
            case "status" -> handleStatus(sender);
            case "version" -> handleVersion(sender);
            case "reload" -> handleReload(sender);
            case "recommendations", "recommend" -> handleRecommendations(sender);
            case "diagnose" -> handleDiagnose(sender, args);
            case "profile" -> handleProfile(sender, args);
            case "chunks" -> handleChunks(sender);
            case "entities" -> handleEntities(sender);
            case "debug" -> handleDebug(sender, args);
            default -> {
                sendError(sender, "§cUnknown subcommand. Available: status, version, reload, "
                        + "recommendations, diagnose, profile, chunks, entities, debug");
                yield true;
            }
        };
    }

    private boolean handleStatus(CommandSender sender) {
        HealthSnapshot snapshot = plugin.getSnapshotCache().getLatest();

        sendRaw(sender, "§8§m                    §r §6§lOPTIMIZER STATUS§r §8§m                    §r");
        sendRaw(sender, "");
        sendRaw(sender, "§e§lConfiguration:");
        sendRaw(sender, "  §7Profile: §f" + plugin.getConfigManager().getActiveProfile().name().toLowerCase());
        sendRaw(sender, "  §7Health Interval: §f" + plugin.getConfigManager().getHealthInterval() + " ticks");
        sendRaw(sender, "  §7Modules Loaded: §f" + plugin.getModuleManager().getModuleCount());
        sendRaw(sender, "");

        if (snapshot != null) {
            sendRaw(sender, "§e§lCurrent Performance:");
            sendRaw(sender, "  §7TPS: §f" + formatDecimal(snapshot.getTps(), 2));
            sendRaw(sender, "  §7MSPT: §f" + formatDecimal(snapshot.getMspt(), 2) + " ms");
            String statusColor = getStatusColor(snapshot.getServerStatus());
            sendRaw(sender, "  §7Status: " + statusColor + snapshot.getServerStatus());
            sendRaw(sender, "");
        }

        sendRaw(sender, "§e§lFeatures:");
        sendRaw(sender, "  §7Alerts: " + (plugin.getConfigManager().areAlertsEnabled() ? "§aEnabled" : "§cDisabled"));
        sendRaw(sender, "  §7Fallback: " + (plugin.getConfigManager().isFallbackEnabled() ? "§aEnabled" : "§cDisabled"));
        sendRaw(sender, "  §7Self-Monitoring: " + (plugin.getConfigManager().isSelfMonitoringEnabled() ? "§aEnabled" : "§cDisabled"));
        sendRaw(sender, "");
        sendRaw(sender, "§8§m                                                      §r");

        return true;
    }

    private boolean handleVersion(CommandSender sender) {
        sendRaw(sender, "§8§m                    §r §6§lSERVERTUNE§r §8§m                    §r");
        sendRaw(sender, "");
        PluginMeta meta = plugin.getPluginMeta();
        sendRaw(sender, "  §7Version: §f" + meta.getVersion());
        sendRaw(sender, "  §7Author: §f" + String.join(", ", meta.getAuthors()));
        sendRaw(sender, "  §7API Version: §f" + meta.getAPIVersion());
        sendRaw(sender, "");
        sendRaw(sender, "  §7Server: §f" + plugin.getServer().getName());
        sendRaw(sender, "  §7Version: §f" + plugin.getServer().getVersion());
        sendRaw(sender, "  §7Bukkit Version: §f" + plugin.getServer().getBukkitVersion());
        sendRaw(sender, "");
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!checkPermission(sender, "optimizer.reload")) {
            return true;
        }

        sendMessage(sender, "§eReloading configuration...");

        try {
            // Reload configuration
            plugin.getConfigManager().reloadConfig();

            // Push the new config into the components that cache it. Each holds thresholds read
            // once at construction, so without this a reload changed config.yml and nothing else:
            // alerts kept firing on the old thresholds and the budgets stayed as they were at
            // startup. The guard is deliberately not reloaded here - see below.
            plugin.getAlertManager().reload();
            plugin.getSelfMonitor().reload();

            // Restart scheduler to pick up new module settings
            plugin.getScheduler().stop();
            plugin.getScheduler().start();

            // Rebuild the diagnostics engine on the new config, then re-register its sample
            // task: scheduler.stop() cleared every task, and start() only knows about the
            // health monitor and the module tasks. Without this, sampling would stop for good
            // after the first reload and every report would age forever.
            DiagnosticsEngine rebuilt = new DiagnosticsEngine(plugin);
            plugin.setDiagnosticsEngine(rebuilt);
            rebuilt.start();

            // Re-apply module config to the modules already registered. Calling
            // initializeModules() here did nothing (it is guarded to run once), so before this
            // a reload silently kept the old module settings.
            plugin.getModuleManager().reloadModules();

            // The guard reloads last, on purpose. Both steps above turn on everything config.yml
            // asks for without knowing the server is struggling, so a reload issued during
            // fallback would put every module and task back to work. Reloading the guard here
            // lets it re-assert the current state over that.
            plugin.getPerformanceGuard().reload();

            sendMessage(sender, "§aConfiguration reloaded successfully!");
            sendMessage(sender, "§7Active profile: §f" + plugin.getConfigManager().getActiveProfile().name().toLowerCase());
            sendMessage(sender, "§7Modules loaded: §f" + plugin.getModuleManager().getModuleCount());
            sendMessage(sender, "§7Performance state: §f" + plugin.getPerformanceGuard().getCurrentState());
            sendMessage(sender, "§7Diagnostics: §fresampling from scratch; the first sample "
                    + "arrives in " + (rebuilt.getSampleIntervalTicks() / 20) + "s");
        } catch (Exception e) {
            sendError(sender, "§cFailed to reload configuration: " + e.getMessage());
            plugin.getLogger().severe("Configuration reload failed: " + e.getMessage());
        }

        return true;
    }

    /**
     * Suggests changes from the last periodic sample, and only suggests. The engine holds no
     * config handle at all, so this path cannot write config.yml even by mistake - the reader
     * has to make the edit themselves.
     */
    private boolean handleRecommendations(CommandSender sender) {
        if (!checkPermission(sender, "optimizer.recommendations")) {
            return true;
        }

        DiagnosticsEngine engine = plugin.getDiagnosticsEngine();
        if (engine == null || !engine.isEnabled()) {
            sendError(sender, "§cDiagnostics are disabled in configuration, so there is nothing to");
            sendError(sender, "§cbuild recommendations from.");
            return true;
        }

        if (!engine.areRecommendationsEnabled()) {
            sendError(sender, "§cRecommendations are disabled in configuration.");
            return true;
        }

        if (engine.getLatestSnapshot() == null) {
            sendError(sender, "§cNo diagnostic sample taken yet. The first one runs within "
                    + (engine.getSampleIntervalTicks() / 20) + "s of startup.");
            return true;
        }

        engine.recommendAsync(report -> {
            if (report.isEmpty()) {
                sendError(sender, "§cNo diagnostic sample available.");
                return;
            }
            DiagnosticsRenderer.renderRecommendations(sender, report);
        });

        return true;
    }

    /**
     * Same diagnosis as {@code /serverhealth diagnose}, reached from the optimizer command so
     * an operator working here does not have to switch commands. Shares the one periodic
     * sample; neither path scans anything of its own.
     *
     * <p>{@code diagnose plugins} is the one exception: it is the only command in ServerTune
     * that starts a measurement rather than reading one, so it is routed separately and carries
     * its own permission.
     */
    private boolean handleDiagnose(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("plugins")) {
            return handleDiagnosePlugins(sender, args);
        }

        if (!checkPermission(sender, "optimizer.diagnose")) {
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

    /**
     * {@code /servertune diagnose plugins [seconds|cancel]}.
     *
     * <p>The only command that starts profiling. It gets its own permission node rather than
     * sharing {@code optimizer.diagnose}, because everything else under {@code diagnose} reads a
     * sample that already exists and costs nothing, while this one temporarily wraps other
     * plugins' event handlers. The node sits in the existing {@code optimizer.*} namespace
     * rather than a new {@code servertune.*} one, because that is where every other node this
     * command tree checks already lives.
     */
    private boolean handleDiagnosePlugins(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "optimizer.diagnose.plugins")) {
            return true;
        }

        PluginDiagnosticsService service = plugin.getPluginDiagnostics();
        if (service == null) {
            sendError(sender, "§cPlugin diagnostics are unavailable.");
            return true;
        }

        if (args.length >= 3 && args[2].equalsIgnoreCase("cancel")) {
            if (service.cancel()) {
                sendMessage(sender, "§7Cancelling plugin diagnostic...");
            } else {
                sendError(sender, "§cNo plugin diagnostic is running.");
            }
            return true;
        }

        int requested = 0;
        if (args.length >= 3) {
            try {
                requested = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sendError(sender, "§cExpected a duration in seconds or 'cancel', got: " + args[2]);
                return true;
            }
        }

        PluginScanSettings settings = service.settings();
        // Reported before starting, so an operator who asked for 300 seconds learns they are
        // getting the configured maximum instead of silently waiting for the wrong window.
        int resolved = settings.resolveDuration(requested);
        if (requested > 0 && resolved != requested) {
            sendMessage(sender, "§7Requested " + requested + "s; running for " + resolved
                    + "s (configured limits: " + PluginScanSettings.MIN_DURATION_SECONDS + "-"
                    + settings.maxDurationSeconds() + "s).");
        }

        PluginDiagnosticsService.StartResult result = service.start(sender, requested);
        switch (result) {
            case STARTED -> sendRaw(sender, PluginScanFormatter.startedLine(resolved));
            case ALREADY_RUNNING -> sendError(sender, "§cPlugin diagnostic is already running.");
            case DISABLED -> sendError(sender, "§cPlugin diagnostics are disabled in "
                    + "configuration (diagnostics.plugin-scan.enabled).");
            case SERVER_CRITICAL -> sendError(sender, "§cRefusing to start: the server is in "
                    + "fallback. Profiling a server that is already struggling would make both "
                    + "the problem and the measurement worse.");
            case FAILED -> sendError(sender, "§cFailed to start the plugin diagnostic. See the "
                    + "server log; nothing was left instrumented.");
        }
        return true;
    }

    /**
     * Shows the active profile and the intervals it resolves to. Read-only: switching profile
     * means editing config.yml and reloading, so this cannot change server behaviour by
     * accident.
     */
    private boolean handleProfile(CommandSender sender, String[] args) {
        PerformanceProfile active = plugin.getConfigManager().getActiveProfile();

        // A named profile is inspected, not applied. fromString falls back to BALANCED for
        // anything unrecognised, which would quietly report the wrong profile, so the name is
        // matched explicitly and a miss is reported as a miss.
        if (args.length > 1) {
            String requested = args[1].toLowerCase();
            for (PerformanceProfile profile : PerformanceProfile.values()) {
                if (profile.name().toLowerCase().equals(requested)) {
                    return showProfileDetail(sender, profile, active);
                }
            }
            sendError(sender, "§cUnknown profile '" + args[1] + "'. Available: light, balanced, "
                    + "aggressive, custom.");
            return true;
        }

        sendRaw(sender, "§8§m                    §r §6§lPERFORMANCE PROFILE§r §8§m                    §r");
        sendRaw(sender, "");
        sendRaw(sender, "  §7Active: §f" + active.name().toLowerCase());
        sendRaw(sender, "  §7Health sampling: §f"
                + plugin.getConfigManager().getHealthInterval() + " ticks");
        sendRaw(sender, "  §7Deep analysis: §f"
                + plugin.getConfigManager().getDeepAnalysisInterval() + " ticks");
        sendRaw(sender, "");

        sendRaw(sender, "§e§lAvailable profiles:");
        for (PerformanceProfile profile : PerformanceProfile.values()) {
            String marker = profile == active ? "§a> " : "  ";
            String detail = profile == PerformanceProfile.CUSTOM
                    ? "§8reads performance.intervals.* directly"
                    : "§8health " + profile.getHealthInterval() + "t, deep "
                            + profile.getDeepAnalysisInterval() + "t";
            sendRaw(sender, marker + "§7" + profile.name().toLowerCase() + " " + detail);
        }

        sendRaw(sender, "");
        sendRaw(sender, "§8Change with performance.profile in config.yml, then /optimizer reload.");
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    /**
     * Reports one named profile. Still read-only: naming a profile shows what it would
     * schedule, it does not select it.
     */
    private boolean showProfileDetail(CommandSender sender, PerformanceProfile profile,
                                      PerformanceProfile active) {
        sendRaw(sender, "§8§m                    §r §6§lPERFORMANCE PROFILE§r §8§m                    §r");
        sendRaw(sender, "");
        sendRaw(sender, "  §7Profile: §f" + profile.name().toLowerCase()
                + (profile == active ? " §a(active)" : " §8(not active)"));

        if (profile == PerformanceProfile.CUSTOM) {
            sendRaw(sender, "  §7Health sampling: §8reads performance.intervals.health");
            sendRaw(sender, "  §7Deep analysis: §8reads performance.intervals.deep-analysis");
        } else {
            sendRaw(sender, "  §7Health sampling: §f" + profile.getHealthInterval() + " ticks");
            sendRaw(sender, "  §7Deep analysis: §f" + profile.getDeepAnalysisInterval() + " ticks");
        }

        sendRaw(sender, "");
        if (profile != active) {
            sendRaw(sender, "§8Not applied. Set performance.profile: " + profile.name().toLowerCase()
                    + " in config.yml,");
            sendRaw(sender, "§8then /optimizer reload.");
        }
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    /**
     * Chunk totals from the event-driven tracker plus whatever the last diagnostic sample
     * found. Never calls {@code World#getLoadedChunks()}, which would allocate an array of
     * every loaded chunk just to read its length.
     */
    private boolean handleChunks(CommandSender sender) {
        HealthSnapshot snapshot = plugin.getSnapshotCache().getLatest();
        if (snapshot == null) {
            sendError(sender, "§cNo health data available yet. Please wait a moment...");
            return true;
        }

        sendRaw(sender, "§8§m                    §r §6§lOPTIMIZER CHUNKS§r §8§m                    §r");
        sendRaw(sender, "");
        sendRaw(sender, "  §7Loaded chunks: §f" + snapshot.getLoadedChunks());
        sendRaw(sender, "  §7Worlds: §f" + snapshot.getLoadedWorlds());
        sendRaw(sender, "");

        sendRaw(sender, "§e§lPer-World:");
        for (World world : Bukkit.getWorlds()) {
            sendRaw(sender, "  §7" + world.getName() + ": §f"
                    + plugin.getChunkTracker().getLoadedChunkCount(world.getName()) + " chunks");
        }
        sendRaw(sender, "");

        OptimizationModule module = plugin.getModuleManager().getModule("chunk-optimization");
        sendRaw(sender, "§e§lChunk optimization module:");
        sendRaw(sender, "  §7State: " + describeModuleState(module));
        if (module != null && module.getState() != ModuleState.ENABLED) {
            sendRaw(sender, "  §8Enable with optimization.modules.chunk.enabled in config.yml.");
        }

        sendRaw(sender, "");
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    /**
     * Entity totals from the cached snapshot. The counts come from the event-driven
     * {@code EntityCollector}, so this triggers no world scan.
     */
    private boolean handleEntities(CommandSender sender) {
        HealthSnapshot snapshot = plugin.getSnapshotCache().getLatest();
        if (snapshot == null) {
            sendError(sender, "§cNo health data available yet. Please wait a moment...");
            return true;
        }

        sendRaw(sender, "§8§m                    §r §6§lOPTIMIZER ENTITIES§r §8§m                    §r");
        sendRaw(sender, "");
        sendRaw(sender, "  §7Total entities: §f" + snapshot.getTotalEntities());
        sendRaw(sender, "");

        sendRaw(sender, "§e§lTop types:");
        snapshot.getEntityCounts().entrySet().stream()
                .sorted(Map.Entry.<EntityType, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(entry -> sendRaw(sender, "  §7" + entry.getKey().name().toLowerCase()
                        + ": §f" + entry.getValue()));
        sendRaw(sender, "");

        sendRaw(sender, "§e§lEntity-facing modules:");
        for (String name : new String[]{"entity-optimization", "item-optimization",
                "xp-optimization", "projectile-optimization"}) {
            sendRaw(sender, "  §7" + name + ": "
                    + describeModuleState(plugin.getModuleManager().getModule(name)));
        }

        sendRaw(sender, "");
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    private String describeModuleState(OptimizationModule module) {
        if (module == null) {
            return "§8not registered";
        }
        return switch (module.getState()) {
            case ENABLED -> "§aenabled";
            case SUSPENDED -> "§esuspended";
            case DISABLED -> "§cdisabled";
        };
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "optimizer.admin")) {
            return true;
        }

        // /servertune debug selfmonitor ... - the runtime logging switch. Anything else falls
        // through to the read-only timing dump this subcommand has always printed.
        if (args.length > 1 && args[1].equalsIgnoreCase("selfmonitor")) {
            return handleSelfMonitorDebug(sender, args);
        }

        sendRaw(sender, "§8§m                    §r §6§lDEBUG INFO§r §8§m                    §r");
        sendRaw(sender, "");

        sendRaw(sender, "§e§lPlugin Performance:");
        Map<String, SubsystemMetrics> metrics = plugin.getSelfMonitor().getAllMetrics();

        if (metrics.isEmpty()) {
            sendRaw(sender, "  §7No performance data collected yet.");
        } else {
            metrics.forEach((name, metric) -> {
                sendRaw(sender, String.format("  §7%s: §f%.2f ms §8(avg: %.2f ms, max: %.2f ms)",
                        name,
                        metric.getRecentExecutionMs(),
                        metric.getAverageExecutionMs(),
                        metric.getMaxExecutionMs()
                ));
            });

            double total = plugin.getSelfMonitor().getTotalAverageExecutionTime();
            sendRaw(sender, "");
            sendRaw(sender, "  §7Total Average: §f" + formatDecimal(total, 2) + " ms");
        }

        sendRaw(sender, "");
        sendRaw(sender, "§8Console logging: " + describeLoggingState());
        sendRaw(sender, "§8§m                                                      §r");

        return true;
    }

    /**
     * The runtime switch for SelfMonitor console logging.
     *
     * <p>Changes a field on the live policy and nothing else: config.yml is not written, no task
     * is scheduled, no listener is registered and no measurement is affected. The change lasts
     * until the next restart or {@code /optimizer reload}, both of which rebuild the policy from
     * config. That is why it is safe to hand out - it cannot leave a server logging forever
     * because somebody forgot to turn it off.
     *
     * <p>Already gated by {@code optimizer.admin} in {@link #handleDebug}; checked again here so
     * the gate holds whichever route reached this method.
     */
    private boolean handleSelfMonitorDebug(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "optimizer.admin")) {
            return true;
        }

        BudgetWarningPolicy policy = plugin.getSelfMonitor().getWarningPolicy();
        String action = args.length > 2 ? args[2].toLowerCase() : "status";

        switch (action) {
            case "on" -> {
                policy.setRuntimeLogging(true);
                sendMessage(sender, "§aRuntime debug mode enabled. This does not modify configuration.");
                sendMessage(sender, "§7SelfMonitor console logging is on until restart or reload.");
                if (!policy.getConfigured().overBudget()) {
                    sendMessage(sender, "§eNote: self-monitoring.logging.over-budget is false in "
                            + "config.yml, so over-budget warnings stay silent.");
                }
                sendMessage(sender, "§7Warnings are rate-limited to one per subsystem per "
                        + policy.getConfigured().cooldownSeconds() + "s.");
            }
            case "off" -> {
                policy.setRuntimeLogging(false);
                sendMessage(sender, "§aRuntime debug mode disabled. This does not modify configuration.");
                sendMessage(sender, "§7Monitoring, over-budget detection and module suspension "
                        + "all continue as before.");
            }
            case "verbose" -> {
                return handleSelfMonitorVerbose(sender, args, policy);
            }
            case "status" -> {
                return showSelfMonitorStatus(sender, policy);
            }
            default -> sendError(sender, "§cUsage: /servertune debug selfmonitor <on|off|status|verbose>");
        }

        return true;
    }

    /**
     * Verbose adds fields to warnings that are already being printed. It does not enable logging,
     * does not shorten the cooldown, and produces no periodic output of its own - there is no
     * per-tick logging mode to reach from here.
     */
    private boolean handleSelfMonitorVerbose(CommandSender sender, String[] args,
                                             BudgetWarningPolicy policy) {
        // Bare "verbose" toggles; an explicit on/off sets it, so a script can be idempotent.
        boolean turnOn = args.length > 3
                ? args[3].equalsIgnoreCase("on")
                : !policy.isVerbose();

        if (args.length > 3 && !args[3].equalsIgnoreCase("on") && !args[3].equalsIgnoreCase("off")) {
            sendError(sender, "§cUsage: /servertune debug selfmonitor verbose [on|off]");
            return true;
        }

        policy.setRuntimeVerbose(turnOn);
        sendMessage(sender, "§aRuntime debug mode " + (turnOn ? "enabled" : "disabled")
                + " (verbose). This does not modify configuration.");

        if (turnOn) {
            sendMessage(sender, "§7Verbose adds averages and execution counts to warnings that "
                    + "are already printed. It does not add new log lines.");
            if (!policy.isLoggingActive()) {
                sendMessage(sender, "§eConsole logging is off, so nothing will print. Turn it on "
                        + "with §f/servertune debug selfmonitor on§e.");
            }
        }

        return true;
    }

    /** Reads the policy's current state. Measures nothing, writes nothing. */
    private boolean showSelfMonitorStatus(CommandSender sender, BudgetWarningPolicy policy) {
        SelfMonitorLogging configured = policy.getConfigured();

        sendRaw(sender, "§8§m                    §r §6§lSELFMONITOR LOGGING§r §8§m                    §r");
        sendRaw(sender, "");
        sendRaw(sender, "  §7Console logging: " + describeLoggingState());
        sendRaw(sender, "  §7Over-budget warnings: "
                + (policy.isOverBudgetLoggingActive() ? "§aON" : "§cOFF"));
        sendRaw(sender, "  §7Recovery messages: "
                + (policy.isRecoveryLoggingActive() ? "§aON" : "§cOFF"));
        sendRaw(sender, "  §7Verbose: " + (policy.isVerbose() ? "§aON" : "§cOFF"));
        sendRaw(sender, "  §7Cooldown: §f" + configured.cooldownSeconds()
                + "s §8per subsystem, independently");
        sendRaw(sender, "");

        sendRaw(sender, "§e§lFrom config.yml:");
        sendRaw(sender, "  §7logging.enabled: §f" + configured.enabled());
        sendRaw(sender, "  §7logging.over-budget: §f" + configured.overBudget());
        sendRaw(sender, "  §7logging.recovery: §f" + configured.recovery());
        sendRaw(sender, "  §7logging.verbose: §f" + configured.verbose());
        sendRaw(sender, "");

        if (policy.hasRuntimeOverride()) {
            sendRaw(sender, "§8A runtime override is active. config.yml is unchanged; the override");
            sendRaw(sender, "§8is dropped on restart or /optimizer reload.");
        } else {
            sendRaw(sender, "§8No runtime override; config.yml decides.");
        }
        sendRaw(sender, "§8Monitoring itself is "
                + (policy.isMonitoringEnabled() ? "on" : "off")
                + " and is not affected by these settings.");
        sendRaw(sender, "§8§m                                                      §r");
        return true;
    }

    /** Shared by both debug views so they can never disagree about the current state. */
    private String describeLoggingState() {
        BudgetWarningPolicy policy = plugin.getSelfMonitor().getWarningPolicy();
        return switch (policy.getLoggingSource()) {
            case CONFIG -> "§aON §8(config.yml)";
            case RUNTIME -> "§aON §8(runtime override, not saved)";
            case RUNTIME_OFF -> "§cOFF §8(runtime override, not saved)";
            case OFF -> "§cOFF";
        };
    }
}
