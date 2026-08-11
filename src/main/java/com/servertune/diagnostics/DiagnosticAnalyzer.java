package com.servertune.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link DiagnosticSnapshot} into a {@link DiagnosticReport}.
 *
 * <p>Pure: no Bukkit, no clock of its own, no mutable state. Safe to call from any thread
 * and unit testable without a server, in the same shape as {@code PerformanceStateMachine}.
 *
 * <p>Findings state measurements. Where a plausible contributor exists it is worded as one
 * ("N of them are dropped items"), never as a cause - the plugin reads counters, it does
 * not profile the tick loop, so it cannot show what a tick was actually spent on.
 */
public final class DiagnosticAnalyzer {

    private final DiagnosticThresholds thresholds;

    public DiagnosticAnalyzer(DiagnosticThresholds thresholds) {
        this.thresholds = thresholds;
    }

    public DiagnosticThresholds getThresholds() {
        return thresholds;
    }

    /**
     * @param snapshot the frozen measurements, or null when none has been taken yet
     * @param now      wall clock used only to age the snapshot
     */
    public DiagnosticReport analyze(DiagnosticSnapshot snapshot, long now) {
        if (snapshot == null) {
            return DiagnosticReport.unavailable();
        }

        List<Finding> findings = new ArrayList<>();
        analyzeTps(snapshot, findings);
        analyzeMspt(snapshot, findings);
        analyzeCpu(snapshot, findings);
        analyzeMemory(snapshot, findings);
        analyzeChunks(snapshot, findings);
        analyzeEntities(snapshot, findings);
        analyzeBlockEntities(snapshot, findings);

        findings.sort(Comparator.comparingInt(
                (Finding f) -> f.severity().ordinal()).reversed());

        DiagnosticSeverity worst = DiagnosticSeverity.OK;
        for (Finding f : findings) {
            worst = DiagnosticSeverity.max(worst, f.severity());
        }

        long age = snapshot.getAgeMillis(now);
        return new DiagnosticReport(snapshot, findings, snapshot.getHotspots(), worst,
                age, age > thresholds.getStaleAfterMillis());
    }

    private void analyzeTps(DiagnosticSnapshot s, List<Finding> out) {
        double tps = s.getTps();
        String value = fmt(tps) + " TPS";

        if (tps < thresholds.getTpsSevere()) {
            out.add(Finding.of(DiagnosticSeverity.CRITICAL, "TPS", value + " - severely below 20",
                    "The server is running at roughly " + pct(tps / 20.0) + " of normal speed."));
        } else if (tps < thresholds.getTpsCritical()) {
            out.add(Finding.of(DiagnosticSeverity.HIGH, "TPS", value + " - well below 20"));
        } else if (tps < thresholds.getTpsWarning()) {
            out.add(Finding.of(DiagnosticSeverity.MEDIUM, "TPS", value + " - below 20"));
        } else if (tps < 19.5) {
            out.add(Finding.of(DiagnosticSeverity.LOW, "TPS", value + " - marginally below 20"));
        }
    }

    private void analyzeMspt(DiagnosticSnapshot s, List<Finding> out) {
        double mspt = s.getMspt();
        // 50ms is one full tick. Above it the server cannot hold 20 TPS; below it there is
        // headroom, so only report against thresholds that sit meaningfully above 50.
        String value = fmt(mspt) + " ms per tick";

        if (mspt > thresholds.getMsptCritical()) {
            out.add(Finding.of(DiagnosticSeverity.HIGH, "MSPT", value,
                    "A tick's budget is 50 ms, so ticks are overrunning by "
                            + fmt(mspt - 50.0) + " ms."));
        } else if (mspt > thresholds.getMsptWarning()) {
            out.add(Finding.of(DiagnosticSeverity.MEDIUM, "MSPT", value,
                    "Above the 50 ms tick budget."));
        } else if (mspt > 50.0) {
            out.add(Finding.of(DiagnosticSeverity.LOW, "MSPT", value,
                    "Marginally above the 50 ms tick budget."));
        }
    }

    private void analyzeCpu(DiagnosticSnapshot s, List<Finding> out) {
        double cpu = s.getCpuUsage();

        if (cpu >= thresholds.getCpuCritical()) {
            out.add(Finding.of(DiagnosticSeverity.HIGH, "CPU",
                    pct(cpu / 100.0) + " CPU in this JVM process"));
        } else if (cpu >= thresholds.getCpuWarning()) {
            out.add(Finding.of(DiagnosticSeverity.MEDIUM, "CPU",
                    pct(cpu / 100.0) + " CPU in this JVM process"));
        }
    }

    private void analyzeMemory(DiagnosticSnapshot s, List<Finding> out) {
        if (s.getMemoryMax() <= 0) {
            return;
        }

        double used = s.getMemoryUsedPercent();
        String value = fmt(used) + "% of max heap in use";

        if (used >= thresholds.getMemoryCriticalPercent()) {
            out.add(Finding.of(DiagnosticSeverity.HIGH, "Memory", value,
                    "Heap pressure this high usually means more frequent GC pauses."));
        } else if (used >= thresholds.getMemoryWarningPercent()) {
            out.add(Finding.of(DiagnosticSeverity.MEDIUM, "Memory", value));
        }
    }

    private void analyzeChunks(DiagnosticSnapshot s, List<Finding> out) {
        int loaded = s.getLoadedChunks();

        if (loaded >= thresholds.getLoadedChunksCritical()) {
            out.add(Finding.of(DiagnosticSeverity.HIGH, "Chunks", loaded + " chunks loaded"));
        } else if (loaded >= thresholds.getLoadedChunksWarning()) {
            out.add(Finding.of(DiagnosticSeverity.MEDIUM, "Chunks", loaded + " chunks loaded"));
        }

        int rate = s.getChunkLoadRate();
        if (rate >= thresholds.getChunkLoadRateCritical()) {
            out.add(Finding.of(DiagnosticSeverity.HIGH, "Chunk loading",
                    rate + " chunks loaded per second",
                    "Unloading " + s.getChunkUnloadRate() + " per second over the same period."));
        } else if (rate >= thresholds.getChunkLoadRateWarning()) {
            out.add(Finding.of(DiagnosticSeverity.MEDIUM, "Chunk loading",
                    rate + " chunks loaded per second"));
        }

        if (s.getInactiveChunks() >= thresholds.getInactiveChunksWarning()) {
            out.add(Finding.of(DiagnosticSeverity.LOW, "Chunks",
                    s.getInactiveChunks() + " tracked chunks have had no player activity"));
        }

        if (s.getForceLoadedChunks() > 0) {
            out.add(Finding.of(DiagnosticSeverity.LOW, "Chunks",
                    s.getForceLoadedChunks() + " chunks are force-loaded",
                    "Force-loaded chunks stay loaded regardless of player presence."));
        }
    }

    private void analyzeEntities(DiagnosticSnapshot s, List<Finding> out) {
        int total = s.getTotalEntities();
        List<String> breakdown = new ArrayList<>();
        if (s.getItemCount() > 0) {
            breakdown.add(s.getItemCount() + " dropped items");
        }
        if (s.getXpOrbCount() > 0) {
            breakdown.add(s.getXpOrbCount() + " XP orbs");
        }
        if (s.getMobCount() > 0) {
            breakdown.add(s.getMobCount() + " mobs");
        }
        if (s.getVillagerCount() > 0) {
            breakdown.add(s.getVillagerCount() + " villagers");
        }

        if (total >= thresholds.getEntitiesCritical()) {
            out.add(new Finding(DiagnosticSeverity.HIGH, "Entities",
                    total + " entities loaded", breakdown));
        } else if (total >= thresholds.getEntitiesWarning()) {
            out.add(new Finding(DiagnosticSeverity.MEDIUM, "Entities",
                    total + " entities loaded", breakdown));
        }

        if (s.getItemCount() >= thresholds.getItemsCritical()) {
            out.add(Finding.of(DiagnosticSeverity.HIGH, "Items",
                    s.getItemCount() + " dropped items on the ground"));
        } else if (s.getItemCount() >= thresholds.getItemsWarning()) {
            out.add(Finding.of(DiagnosticSeverity.MEDIUM, "Items",
                    s.getItemCount() + " dropped items on the ground"));
        }

        if (s.getXpOrbCount() >= thresholds.getXpOrbsWarning()) {
            out.add(Finding.of(DiagnosticSeverity.MEDIUM, "XP orbs",
                    s.getXpOrbCount() + " XP orbs on the ground"));
        }
        if (s.getMobCount() >= thresholds.getMobsWarning()) {
            out.add(Finding.of(DiagnosticSeverity.MEDIUM, "Mobs",
                    s.getMobCount() + " mobs loaded"));
        }
        if (s.getVillagerCount() >= thresholds.getVillagersWarning()) {
            out.add(Finding.of(DiagnosticSeverity.MEDIUM, "Villagers",
                    s.getVillagerCount() + " villagers loaded"));
        }

        topEntityType(s.getEntityCounts()).ifPresent(e -> {
            if (e.getValue() >= thresholds.getItemsWarning()) {
                out.add(Finding.of(DiagnosticSeverity.LOW, "Entities",
                        "Most common entity type: " + e.getKey() + " (" + e.getValue() + ")"));
            }
        });
    }

    private void analyzeBlockEntities(DiagnosticSnapshot s, List<Finding> out) {
        if (s.hasHopperCount() && s.getHopperCount() >= thresholds.getHoppersWarning()) {
            List<String> details = new ArrayList<>();
            if (s.getHopperTransfersPerWindow() > 0) {
                details.add(s.getHopperTransfersPerWindow()
                        + " transfers observed in the last sampling window");
            }
            out.add(new Finding(DiagnosticSeverity.MEDIUM, "Hoppers",
                    s.getHopperCount() + " hoppers tracked", details));
        }

        if (s.hasBlockEntityCount()
                && s.getBlockEntityCount() >= thresholds.getBlockEntitiesWarning()) {
            out.add(Finding.of(DiagnosticSeverity.MEDIUM, "Block entities",
                    s.getBlockEntityCount() + " block entities tracked"));
        }

        if (s.getRedstoneActivityPerWindow() >= thresholds.getHotspotRedstoneActivity()) {
            out.add(Finding.of(DiagnosticSeverity.LOW, "Redstone",
                    s.getRedstoneActivityPerWindow()
                            + " redstone events observed in the last sampling window"));
        }
    }

    private java.util.Optional<Map.Entry<String, Integer>> topEntityType(
            Map<String, Integer> counts) {
        return counts.entrySet().stream().max(Map.Entry.comparingByValue());
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    private static String pct(double ratio) {
        return String.format("%.0f%%", ratio * 100.0);
    }
}
