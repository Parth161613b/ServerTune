package com.servertune.command;

import com.servertune.diagnostics.ChunkHotspot;
import com.servertune.diagnostics.DiagnosticReport;
import com.servertune.diagnostics.DiagnosticSeverity;
import com.servertune.diagnostics.DiagnosticSnapshot;
import com.servertune.recommendation.Recommendation;
import com.servertune.recommendation.RecommendationReport;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Renders diagnostic and recommendation reports to a command sender.
 *
 * <p>Two rules the wording follows throughout:
 * <ul>
 *   <li>a flagged chunk is a "Potential hotspot" and its lines are measurements. Nothing
 *       here says a chunk causes lag, because the plugin counts things - it does not profile
 *       the tick loop and so cannot attribute a tick cost to anything</li>
 *   <li>the age of the sample is always printed. Every number shown came from the last
 *       periodic sample, not from a scan the command ran</li>
 * </ul>
 */
public final class DiagnosticsRenderer {

    private static final String DIVIDER = "§8§m                                                      §r";

    private DiagnosticsRenderer() {
    }

    public static void renderDiagnosis(CommandSender sender, DiagnosticReport report) {
        header(sender, "DIAGNOSTICS");

        DiagnosticSnapshot s = report.snapshot();

        sender.sendMessage("§e§lPerformance:");
        sender.sendMessage("  §7TPS: §f" + dec(s.getTps()) + " §8/ 20.00");
        sender.sendMessage("  §7MSPT: §f" + dec(s.getMspt()) + " ms §8(50.00 ms = one tick)");
        sender.sendMessage("  §7CPU: §f" + dec(s.getCpuUsage()) + "% §8(JVM process)");
        if (s.getMemoryMax() > 0) {
            sender.sendMessage("  §7RAM: §f" + mb(s.getMemoryUsed()) + " §7/§f " + mb(s.getMemoryMax())
                    + " MB §8(" + dec(s.getMemoryUsedPercent()) + "%)");
        }
        sender.sendMessage("  §7Guard state: §f" + s.getGuardState());
        sender.sendMessage("");

        sender.sendMessage("§e§lWorld:");
        sender.sendMessage("  §7Loaded chunks: §f" + s.getLoadedChunks());
        sender.sendMessage("  §7Chunk load rate: §f" + s.getChunkLoadRate() + "/s §8(unloading "
                + s.getChunkUnloadRate() + "/s)");
        if (s.getTrackedChunks() > 0) {
            sender.sendMessage("  §7Inactive chunks: §f" + s.getInactiveChunks() + " §8of "
                    + s.getTrackedChunks() + " tracked");
        }
        if (s.getForceLoadedChunks() > 0) {
            sender.sendMessage("  §7Force-loaded chunks: §f" + s.getForceLoadedChunks());
        }
        sender.sendMessage("  §7Players: §f" + s.getOnlinePlayers() + " §7/§f " + s.getMaxPlayers());
        sender.sendMessage("");

        sender.sendMessage("§e§lEntities:");
        sender.sendMessage("  §7Total: §f" + s.getTotalEntities());
        sender.sendMessage("  §7Dropped items: §f" + s.getItemCount());
        sender.sendMessage("  §7XP orbs: §f" + s.getXpOrbCount());
        sender.sendMessage("  §7Mobs: §f" + s.getMobCount());
        sender.sendMessage("  §7Villagers: §f" + s.getVillagerCount());
        sender.sendMessage("");

        sender.sendMessage("§e§lBlock entities:");
        sender.sendMessage("  §7Hoppers: §f" + measured(s.getHopperCount()));
        sender.sendMessage("  §7Block entities: §f" + measured(s.getBlockEntityCount()));
        if (s.getHopperTransfersPerWindow() > 0) {
            sender.sendMessage("  §7Hopper transfers: §f" + s.getHopperTransfersPerWindow()
                    + " §8in the last sampling window");
        }
        if (s.getRedstoneActivityPerWindow() > 0) {
            sender.sendMessage("  §7Redstone events: §f" + s.getRedstoneActivityPerWindow()
                    + " §8observed");
        }
        sender.sendMessage("");

        sender.sendMessage("§e§lAssessment: " + severityColor(report.severity())
                + report.severity().name());
        if (report.findings().isEmpty()) {
            sender.sendMessage("  §7Nothing measured outside its configured threshold.");
        } else {
            for (var finding : report.findings()) {
                sender.sendMessage("  " + severityColor(finding.severity()) + "• §7"
                        + finding.category() + ": §f" + finding.summary());
                for (String detail : finding.details()) {
                    sender.sendMessage("      §8" + detail);
                }
            }
        }
        sender.sendMessage("");

        renderHotspots(sender, report.hotspots(), s.isPerChunkEntityCountsMeasured());

        footer(sender, report.ageMillis(), report.stale());
        sender.sendMessage("§8No configuration or optimization changes were made.");
        sender.sendMessage(DIVIDER);
    }

    public static void renderHotspots(CommandSender sender, List<ChunkHotspot> hotspots,
                                      boolean entityCountsMeasured) {
        if (hotspots.isEmpty()) {
            sender.sendMessage("§e§lPotential hotspots:");
            sender.sendMessage("  §7None of the sampled chunks were above their thresholds.");
            if (!entityCountsMeasured) {
                sender.sendMessage("  §8Per-chunk entity counts have not been sampled yet.");
            }
            sender.sendMessage("");
            return;
        }

        sender.sendMessage("§e§lPotential hotspots §8(" + hotspots.size() + "):");
        for (ChunkHotspot h : hotspots) {
            sender.sendMessage("  §6Chunk " + h.coordinates() + " §8(" + h.worldName()
                    + ", block " + h.blockX() + "," + h.blockZ() + ")");
            for (String line : h.measurements()) {
                sender.sendMessage("    §7- §f" + line);
            }
        }
        // The one claim worth making explicitly, because the reader will assume otherwise.
        sender.sendMessage("  §8These chunks measured above their thresholds. That is a"
                + " correlation, not");
        sender.sendMessage("  §8a measured cause - the plugin counts objects and events, it does"
                + " not");
        sender.sendMessage("  §8profile the tick loop.");
        sender.sendMessage("");
    }

    public static void renderRecommendations(CommandSender sender, RecommendationReport report) {
        header(sender, "RECOMMENDATIONS");

        if (!report.hasRecommendations()) {
            sender.sendMessage("  §7Nothing to suggest: no measurement is above its threshold.");
            sender.sendMessage("");
            footer(sender, report.ageMillis(), report.stale());
            sender.sendMessage(DIVIDER);
            return;
        }

        for (Recommendation r : report.recommendations()) {
            sender.sendMessage(priorityColor(r.priority()) + "§l" + r.priority().name() + " §r§f"
                    + r.title());
            for (String evidence : r.evidence()) {
                sender.sendMessage("  §7Measured: §f" + evidence);
            }
            if (r.hasConfigPath()) {
                sender.sendMessage("  §7Config: §f" + r.configPath());
            }
            if (r.hasGameplayNote()) {
                sender.sendMessage("  §7Effect: §8" + r.gameplayNote());
            }
            sender.sendMessage("");
        }

        footer(sender, report.ageMillis(), report.stale());
        sender.sendMessage("§8These are suggestions only. Nothing was changed - edit config.yml");
        sender.sendMessage("§8yourself and reload to apply any of them.");
        sender.sendMessage(DIVIDER);
    }

    /** The §4 staleness line, shown on every rendered report. */
    private static void footer(CommandSender sender, long ageMillis, boolean stale) {
        String line = "Data sampled " + (ageMillis / 1000L) + " seconds ago.";
        sender.sendMessage(stale ? "§e" + line : "§8" + line);
    }

    private static void header(CommandSender sender, String title) {
        sender.sendMessage("§8§m                    §r §6§l" + title + "§r §8§m                    §r");
        sender.sendMessage("");
    }

    private static String measured(int value) {
        return value == DiagnosticSnapshot.NOT_MEASURED ? "§8not measured" : String.valueOf(value);
    }

    private static String dec(double value) {
        return String.format("%.2f", value);
    }

    private static String mb(long bytes) {
        return String.valueOf(bytes / (1024 * 1024));
    }

    private static String severityColor(DiagnosticSeverity severity) {
        return switch (severity) {
            case CRITICAL -> "§c";
            case HIGH -> "§6";
            case MEDIUM -> "§e";
            case LOW -> "§a";
            case OK -> "§7";
        };
    }

    private static String priorityColor(Recommendation.Priority priority) {
        return switch (priority) {
            case HIGH -> "§c";
            case MEDIUM -> "§e";
            case LOW -> "§a";
        };
    }
}
