package com.servertune.recommendation;

import com.servertune.diagnostics.ChunkHotspot;
import com.servertune.diagnostics.DiagnosticSnapshot;
import com.servertune.diagnostics.DiagnosticThresholds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Derives suggestions from a frozen {@link DiagnosticSnapshot}.
 *
 * <p>Pure and Bukkit-free, so it can run on an async thread and be unit tested without a
 * server, matching the pure-core pattern used by the guard and diagnostics engines.
 *
 * <p><b>This engine never modifies configuration.</b> It has no reference to the plugin, no
 * config handle, and no scheduler. Every recommendation names the config.yml key an owner
 * would edit and stops there. Turning a suggestion into a change is deliberately a manual
 * step: the plugin cannot know whether the gameplay cost is acceptable on a given server.
 *
 * <p>Recommendations are only produced for a module that is currently off, or for a setting
 * that is currently unset. Suggesting something already enabled is noise.
 */
public final class RecommendationEngine {

    // Module names as registered with ModuleManager.
    private static final String ITEM_MODULE = "item-optimization";
    private static final String XP_MODULE = "xp-optimization";
    private static final String ENTITY_MODULE = "entity-optimization";
    private static final String CHUNK_MODULE = "chunk-optimization";
    private static final String HOPPER_MODULE = "hopper-optimization";
    private static final String REDSTONE_MODULE = "redstone-optimization";

    private final DiagnosticThresholds thresholds;

    public RecommendationEngine(DiagnosticThresholds thresholds) {
        this.thresholds = thresholds;
    }

    public DiagnosticThresholds getThresholds() {
        return thresholds;
    }

    /**
     * @param snapshot the frozen measurements, or null when none has been taken yet
     * @param now      wall clock used only to age the snapshot
     */
    public RecommendationReport recommend(DiagnosticSnapshot snapshot, long now) {
        if (snapshot == null) {
            return RecommendationReport.unavailable();
        }

        List<Recommendation> out = new ArrayList<>();
        recommendItemHandling(snapshot, out);
        recommendXpHandling(snapshot, out);
        recommendChunkUnloading(snapshot, out);
        recommendChunkLoadMonitoring(snapshot, out);
        recommendHopperOptimization(snapshot, out);
        recommendRedstoneMonitoring(snapshot, out);
        recommendEntityLimits(snapshot, out);
        recommendMemory(snapshot, out);

        out.sort(Comparator.comparingInt(
                (Recommendation r) -> r.priority().ordinal()).reversed());

        long age = snapshot.getAgeMillis(now);
        return new RecommendationReport(snapshot, out, age,
                age > thresholds.getStaleAfterMillis());
    }

    private void recommendItemHandling(DiagnosticSnapshot s, List<Recommendation> out) {
        int items = s.getItemCount();
        if (items < thresholds.getItemsWarning()) {
            return;
        }

        if (!s.isModuleEnabled(ITEM_MODULE)) {
            out.add(Recommendation.builder(
                            items >= thresholds.getItemsCritical()
                                    ? Recommendation.Priority.HIGH
                                    : Recommendation.Priority.MEDIUM,
                            "Consider enabling item optimization")
                    .evidence(items + " dropped items are currently loaded")
                    .configPath("optimization.modules.item.enabled")
                    .gameplayNote("Merging combines nearby stacks of the same type; "
                            + "item lifetime limits remove old drops.")
                    .build());
            return;
        }

        if (!s.isItemMergingEnabled()) {
            out.add(Recommendation.builder(Recommendation.Priority.MEDIUM,
                            "Consider enabling item merging")
                    .evidence(items + " dropped items are loaded and the item module is on, "
                            + "but merging is off")
                    .configPath("optimization.modules.item.merging.enabled")
                    .gameplayNote("Nearby stacks of the same type combine into one entity.")
                    .build());
        }
    }

    private void recommendXpHandling(DiagnosticSnapshot s, List<Recommendation> out) {
        if (s.getXpOrbCount() < thresholds.getXpOrbsWarning() || s.isModuleEnabled(XP_MODULE)) {
            return;
        }

        out.add(Recommendation.builder(Recommendation.Priority.MEDIUM,
                        "Consider enabling XP orb optimization")
                .evidence(s.getXpOrbCount() + " XP orbs are currently loaded")
                .configPath("optimization.modules.xp.enabled")
                .gameplayNote("Nearby orbs merge; total XP is preserved.")
                .build());
    }

    private void recommendChunkUnloading(DiagnosticSnapshot s, List<Recommendation> out) {
        if (s.getInactiveChunks() < thresholds.getInactiveChunksWarning()) {
            return;
        }

        if (!s.isModuleEnabled(CHUNK_MODULE)) {
            out.add(Recommendation.builder(Recommendation.Priority.MEDIUM,
                            "Consider enabling inactive chunk unloading")
                    .evidence(s.getInactiveChunks() + " of " + s.getTrackedChunks()
                            + " tracked chunks have had no player activity")
                    .configPath("optimization.modules.chunk.enabled")
                    .gameplayNote("Unloaded chunks stop ticking, so farms and redstone in them "
                            + "pause until a player returns.")
                    .build());
            return;
        }

        if (!s.isInactiveChunkUnloadingEnabled()) {
            out.add(Recommendation.builder(Recommendation.Priority.LOW,
                            "Consider enabling inactive-chunk unloading within the chunk module")
                    .evidence(s.getInactiveChunks() + " tracked chunks have had no player activity")
                    .configPath("optimization.modules.chunk.inactive-unloading.enabled")
                    .gameplayNote("Unloaded chunks stop ticking until a player returns.")
                    .build());
        }
    }

    private void recommendChunkLoadMonitoring(DiagnosticSnapshot s, List<Recommendation> out) {
        if (s.getChunkLoadRate() < thresholds.getChunkLoadRateWarning()) {
            return;
        }

        Recommendation.Builder b = Recommendation.builder(
                        s.getChunkLoadRate() >= thresholds.getChunkLoadRateCritical()
                                ? Recommendation.Priority.HIGH
                                : Recommendation.Priority.MEDIUM,
                        "Consider monitoring chunk loading")
                .evidence(s.getChunkLoadRate() + " chunks are being loaded per second")
                .evidence(s.getChunkUnloadRate() + " are being unloaded per second")
                // No configPath: chunk churn is usually driven by player movement, view
                // distance, or elytra travel, none of which this plugin has a knob for.
                .gameplayNote("High churn is often normal travel. Check view-distance and "
                        + "simulation-distance in server.properties before changing anything.");

        if (s.getForceLoadedChunks() > 0) {
            b.evidence(s.getForceLoadedChunks() + " chunks are force-loaded");
        }

        out.add(b.build());
    }

    private void recommendHopperOptimization(DiagnosticSnapshot s, List<Recommendation> out) {
        if (s.isModuleEnabled(HOPPER_MODULE)) {
            return;
        }

        List<ChunkHotspot> dense = s.getHotspots().stream()
                .filter(h -> h.hopperCount() >= thresholds.getHotspotHoppersPerChunk())
                .toList();

        boolean serverWide = s.hasHopperCount()
                && s.getHopperCount() >= thresholds.getHoppersWarning();

        if (dense.isEmpty() && !serverWide) {
            return;
        }

        Recommendation.Builder b = Recommendation.builder(Recommendation.Priority.MEDIUM,
                        "Consider enabling hopper optimization")
                .configPath("optimization.modules.hopper.enabled")
                .gameplayNote("Throttling and per-chunk limits slow item farms and sorting "
                        + "systems. Hotspot detection alone changes nothing.");

        if (serverWide) {
            b.evidence(s.getHopperCount() + " hoppers are tracked server-wide");
        }
        for (ChunkHotspot h : dense) {
            b.evidence(h.hopperCount() + " hoppers in chunk " + h.coordinates()
                    + " (" + h.worldName() + ")");
        }

        out.add(b.build());
    }

    private void recommendRedstoneMonitoring(DiagnosticSnapshot s, List<Recommendation> out) {
        if (s.isModuleEnabled(REDSTONE_MODULE)) {
            return;
        }

        List<ChunkHotspot> busy = s.getHotspots().stream()
                .filter(h -> h.redstoneActivity() >= thresholds.getHotspotRedstoneActivity())
                .toList();

        if (busy.isEmpty()) {
            return;
        }

        Recommendation.Builder b = Recommendation.builder(Recommendation.Priority.LOW,
                        "Consider enabling redstone monitoring")
                .configPath("optimization.modules.redstone.enabled")
                .gameplayNote("The default 'conservative' mode only observes and never cancels "
                        + "anything, so gameplay is unchanged.");

        for (ChunkHotspot h : busy) {
            b.evidence(h.redstoneActivity() + " redstone events in chunk " + h.coordinates()
                    + " (" + h.worldName() + ") in the last sampling window");
        }

        out.add(b.build());
    }

    private void recommendEntityLimits(DiagnosticSnapshot s, List<Recommendation> out) {
        if (s.isModuleEnabled(ENTITY_MODULE)) {
            return;
        }

        boolean crowded = s.getTotalEntities() >= thresholds.getEntitiesCritical();
        List<ChunkHotspot> dense = s.getHotspots().stream()
                .filter(ChunkHotspot::hasEntityCount)
                .filter(h -> h.entityCount() >= thresholds.getHotspotEntitiesPerChunk())
                .toList();

        if (!crowded && dense.isEmpty()) {
            return;
        }

        Recommendation.Builder b = Recommendation.builder(Recommendation.Priority.MEDIUM,
                        "Consider enabling entity limits")
                .configPath("optimization.modules.entity.enabled")
                .gameplayNote("HIGH gameplay impact: this caps mob density and can stop mob "
                        + "farms working. Enable deliberately.");

        if (crowded) {
            b.evidence(s.getTotalEntities() + " entities are loaded server-wide");
            if (s.getMobCount() > 0) {
                b.evidence(s.getMobCount() + " of them are mobs");
            }
        }
        for (ChunkHotspot h : dense) {
            b.evidence(h.entityCount() + " entities in chunk " + h.coordinates()
                    + " (" + h.worldName() + ")");
        }

        out.add(b.build());
    }

    private void recommendMemory(DiagnosticSnapshot s, List<Recommendation> out) {
        if (s.getMemoryMax() <= 0
                || s.getMemoryUsedPercent() < thresholds.getMemoryCriticalPercent()) {
            return;
        }

        out.add(Recommendation.builder(Recommendation.Priority.HIGH,
                        "Consider raising the JVM heap limit")
                .evidence(String.format("%.1f%% of the max heap is in use",
                        s.getMemoryUsedPercent()))
                .evidence("Max heap is " + (s.getMemoryMax() / (1024 * 1024)) + " MB")
                .gameplayNote("This is a server start-up flag (-Xmx), not a plugin setting.")
                .build());
    }
}
