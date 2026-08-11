package com.servertune.diagnostics;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Turns config.yml into {@link DiagnosticThresholds}.
 *
 * <p>Split out from the engine for the same reason as {@code GuardConfigLoader}:
 * everything the engines decide can then be unit tested by building the value object
 * directly, with no server present.
 */
public final class DiagnosticsConfigLoader {

    private static final String ROOT = "diagnostics";

    private DiagnosticsConfigLoader() {
    }

    public static boolean isEnabled(FileConfiguration config) {
        return config.getBoolean(ROOT + ".enabled", true);
    }

    public static boolean isAsync(FileConfiguration config) {
        return config.getBoolean(ROOT + ".async", true);
    }

    public static boolean areRecommendationsEnabled(FileConfiguration config) {
        return config.getBoolean("recommendations.enabled", true);
    }

    /** How often the periodic sample runs, in ticks. Commands never trigger a sample. */
    public static int getSampleIntervalTicks(FileConfiguration config) {
        return config.getInt(ROOT + ".sampling.interval-ticks",
                config.getInt("performance.intervals.deep-analysis", 1200));
    }

    /**
     * Upper bound on chunks visited by one per-chunk entity pass. Keeps the periodic sample
     * bounded on a server with very many loaded chunks; the pass resumes where it stopped.
     */
    public static int getChunkScanBudget(FileConfiguration config) {
        return config.getInt(ROOT + ".sampling.chunk-scan-budget", 2000);
    }

    public static DiagnosticThresholds loadThresholds(FileConfiguration config) {
        String t = ROOT + ".thresholds.";
        String h = ROOT + ".hotspots.";

        return DiagnosticThresholds.builder()
                // TPS mirrors the guard's ladder so a diagnosis and a guard state agree.
                .tpsWarning(config.getDouble(t + "tps.warning",
                        config.getDouble("performance-guard.states.warning.tps", 18.0)))
                .tpsCritical(config.getDouble(t + "tps.critical",
                        config.getDouble("performance-guard.states.critical.tps", 15.0)))
                .tpsSevere(config.getDouble(t + "tps.severe",
                        config.getDouble("performance-guard.states.fallback.tps", 10.0)))
                // MSPT deliberately does NOT inherit from alerts.mspt.*: 50ms is exactly one
                // tick, so those alerting values (40/50) would report a healthy server as
                // degraded. Inherits the guard's state thresholds instead.
                .msptWarning(config.getDouble(t + "mspt.warning",
                        config.getDouble("performance-guard.states.warning.mspt", 55.0)))
                .msptCritical(config.getDouble(t + "mspt.critical",
                        config.getDouble("performance-guard.states.critical.mspt", 70.0)))
                .cpuWarning(config.getDouble(t + "cpu.warning", 80.0))
                .cpuCritical(config.getDouble(t + "cpu.critical", 95.0))
                .memoryWarningPercent(config.getDouble(t + "memory.warning-percent", 75.0))
                .memoryCriticalPercent(config.getDouble(t + "memory.critical-percent", 90.0))
                .loadedChunksWarning(config.getInt(t + "chunks.loaded-warning", 3000))
                .loadedChunksCritical(config.getInt(t + "chunks.loaded-critical", 5000))
                .chunkLoadRateWarning(config.getInt(t + "chunks.load-rate-warning", 50))
                .chunkLoadRateCritical(config.getInt(t + "chunks.load-rate-critical", 150))
                .inactiveChunksWarning(config.getInt(t + "chunks.inactive-warning", 500))
                .entitiesWarning(config.getInt(t + "entities.total-warning", 3000))
                .entitiesCritical(config.getInt(t + "entities.total-critical", 8000))
                .itemsWarning(config.getInt(t + "entities.items-warning", 500))
                .itemsCritical(config.getInt(t + "entities.items-critical", 2000))
                .xpOrbsWarning(config.getInt(t + "entities.xp-orbs-warning", 300))
                .mobsWarning(config.getInt(t + "entities.mobs-warning", 2000))
                .villagersWarning(config.getInt(t + "entities.villagers-warning", 200))
                .hoppersWarning(config.getInt(t + "block-entities.hoppers-warning", 2000))
                .blockEntitiesWarning(config.getInt(t + "block-entities.total-warning", 8000))
                .hotspotEntitiesPerChunk(config.getInt(h + "entities-per-chunk", 50))
                .hotspotHoppersPerChunk(config.getInt(h + "hoppers-per-chunk",
                        config.getInt("optimization.modules.hopper.hotspot-detection.hoppers-per-chunk", 24)))
                .hotspotBlockEntitiesPerChunk(config.getInt(h + "block-entities-per-chunk", 100))
                .hotspotHopperTransfers(config.getLong(h + "hopper-transfers-per-window",
                        config.getLong("optimization.modules.hopper.hotspot-detection.transfers-per-window", 500)))
                .hotspotRedstoneActivity(config.getLong(h + "redstone-activity-per-window",
                        config.getLong("optimization.modules.redstone.hotspot-detection.activity-per-window", 600)))
                .maxHotspotsReported(config.getInt(h + "max-reported", 5))
                .staleAfterSeconds(config.getInt(ROOT + ".sampling.stale-after-seconds", 30))
                .build();
    }
}
