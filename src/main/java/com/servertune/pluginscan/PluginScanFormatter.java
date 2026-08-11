package com.servertune.pluginscan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the report lines. Pure and Bukkit-free so the wording itself is unit tested - the
 * claims this feature is allowed to make are the part most worth pinning down in a test, and a
 * formatter that needed a live server could not be tested at all.
 *
 * <p>The Bukkit side does nothing but hand each returned line to a {@code CommandSender}, so
 * there is no second place where a sentence could be composed and quietly overclaim.
 */
public final class PluginScanFormatter {

    private static final String DIVIDER =
            "§8§m                                                      §r";

    private PluginScanFormatter() {
    }

    /** The line printed when a session starts. */
    public static String startedLine(int durationSeconds) {
        return "§8[§6ServerTune§8]§r ServerTune plugin diagnostic started. Running for "
                + durationSeconds + " seconds.";
    }

    /** The infrequent progress line (§7). Never sent per tick. */
    public static String progressLine(int elapsedSeconds, int durationSeconds) {
        return "§8[§6ServerTune§8]§r §7Plugin diagnostic running... Elapsed: §f" + elapsedSeconds
                + " §7/ §f" + durationSeconds + " §7seconds";
    }

    public static List<String> render(PluginScanReport report) {
        List<String> out = new ArrayList<>();
        out.add("§8§m                 §r §6§lPLUGIN DIAGNOSTIC§r §8§m                 §r");
        out.add("");

        if (report.outcome() == PluginScanReport.Outcome.ABORTED_CRITICAL) {
            out.add("§cPlugin diagnostic aborted because server performance became critical.");
            out.add("");
        } else if (report.outcome() == PluginScanReport.Outcome.CANCELLED) {
            out.add("§ePlugin diagnostic cancelled. Reporting what was observed before it stopped.");
            out.add("");
        }

        out.add("§e§lWindow:");
        out.add("  §7Duration: §f" + dec(report.elapsedSeconds()) + " s §8of "
                + report.requestedSeconds() + " s requested");
        out.add("  §7Average TPS: §f" + dec(report.averageTps()) + " §8/ 20.00");
        out.add("  §7Average MSPT: §f" + dec(report.averageMspt()) + " ms §8(50.00 ms = one tick)");
        out.add("  §7Peak MSPT: §f" + dec(report.peakMspt()) + " ms");
        out.add("  §7Players: §f" + report.onlinePlayers() + " §8| §7Chunks: §f"
                + report.loadedChunks() + " §8| §7Entities: §f" + report.loadedEntities());
        out.add("  §7Plugins: §f" + report.pluginsEnabled() + " enabled §8of "
                + report.pluginsInstalled() + " installed");
        out.add("");

        renderFindings(report, out);

        out.add("§8" + report.unmeasuredNote());
        if (report.overheadWorthReporting()) {
            out.add("§8ServerTune's own profiling overhead during this window measured "
                    + dec(report.overheadMillis()) + " ms in total.");
        }
        out.add("§7Diagnostic complete. Plugin profiling has stopped.");
        out.add(DIVIDER);
        return out;
    }

    private static void renderFindings(PluginScanReport report, List<String> out) {
        List<PluginFinding> significant = report.significantFindings();

        if (significant.isEmpty()) {
            out.add("§e§lPotential performance sources:");
            out.add("  §7No significant plugin performance source was detected during the");
            out.add("  §7diagnostic window. This does §fNOT §7prove that all plugins are");
            out.add("  §7performance-safe. The diagnostic session has ended and plugin");
            out.add("  §7profiling has stopped.");
            out.add("");
            return;
        }

        out.add("§e§lPotential performance sources §8(" + significant.size() + " of "
                + report.measuredPluginCount() + " measured):");
        int rank = 1;
        for (PluginFinding f : significant) {
            PluginActivity.Measurement m = f.measurement();
            out.add("  " + impactColor(f.impact()) + "§l" + rank + ". " + f.pluginName() + "§r §8- "
                    + f.descriptor());
            out.add("      §7Impact: " + impactColor(f.impact()) + f.impact().label()
                    + " §8| §7Confidence: §f" + f.confidence().name());
            out.add("      §7Observed activity: §f" + f.observation());
            if (m.hasTimedEvidence()) {
                out.add("      §7Average observed execution: §f" + ms(m.averageSyncMillis()));
                out.add("      §7Peak observed execution: §f" + ms(m.peakSyncMillis()));
                out.add("      §7Observed executions: §f" + count(m.syncCalls()));
            }
            if (m.totalTaskCount() > 0) {
                out.add("      §7Scheduled tasks owned: §f" + m.totalTaskCount() + " §8("
                        + m.syncTaskCount() + " sync, " + m.asyncTaskCount()
                        + " async; ownership is not a cost measurement)");
            }
            out.add("      §8Why: " + f.reason());
            out.add("      §7Suggested next step: §8" + f.recommendation());
            rank++;
        }
        out.add("  §8Ranked on measured main-thread handler time. These are observations, not");
        out.add("  §8proof of cause - no plugin here has been shown to make the server slow.");
        out.add("");
    }

    private static String impactColor(Impact impact) {
        return switch (impact) {
            case HIGH -> "§c";
            case MEDIUM -> "§6";
            case LOW -> "§e";
            case NEGLIGIBLE -> "§7";
        };
    }

    private static String dec(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String ms(double value) {
        return String.format(Locale.ROOT, "%.3f ms", value);
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
