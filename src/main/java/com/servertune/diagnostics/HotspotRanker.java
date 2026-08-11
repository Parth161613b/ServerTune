package com.servertune.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ranks candidate chunks by how far their counters sit above the configured thresholds.
 *
 * <p>Pure and Bukkit-free: the caller gathers per-chunk counts on the main thread and hands
 * them here, so ranking can run off-thread.
 *
 * <p>The score is a ratio-of-threshold sum, not a cost estimate. A chunk at twice the
 * entity threshold and twice the hopper threshold scores 4. That makes chunks comparable to
 * each other within one sweep and to nothing else - in particular it is not milliseconds,
 * and must never be presented as if it were.
 */
public final class HotspotRanker {

    private final DiagnosticThresholds thresholds;

    public HotspotRanker(DiagnosticThresholds thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * A chunk's measured counters, before scoring.
     *
     * <p>{@code entityCount} and {@code blockEntityCount} may be {@link ChunkHotspot#NOT_MEASURED}
     * when the tracker that supplies them is not running.
     */
    public record Candidate(String worldName,
                            int chunkX,
                            int chunkZ,
                            int entityCount,
                            int hopperCount,
                            int blockEntityCount,
                            long hopperTransfers,
                            long redstoneActivity,
                            int playerCount,
                            long inactiveSeconds) {
    }

    /**
     * Scores every candidate, keeps those over at least one threshold, and returns the top
     * {@code maxHotspotsReported} by score.
     */
    public List<ChunkHotspot> rank(List<Candidate> candidates) {
        List<ChunkHotspot> flagged = new ArrayList<>();

        for (Candidate c : candidates) {
            double score = score(c);
            if (score <= 0.0) {
                continue;
            }

            flagged.add(ChunkHotspot.builder(c.worldName(), c.chunkX(), c.chunkZ())
                    .entityCount(c.entityCount())
                    .hopperCount(c.hopperCount())
                    .blockEntityCount(c.blockEntityCount())
                    .hopperTransfers(c.hopperTransfers())
                    .redstoneActivity(c.redstoneActivity())
                    .playerCount(c.playerCount())
                    .inactiveSeconds(c.inactiveSeconds())
                    .score(score)
                    .build());
        }

        flagged.sort(Comparator.comparingDouble(ChunkHotspot::score).reversed());

        int limit = Math.max(0, thresholds.getMaxHotspotsReported());
        return flagged.size() <= limit ? List.copyOf(flagged)
                : List.copyOf(flagged.subList(0, limit));
    }

    /**
     * Sum of each counter's ratio to its threshold, counting only counters that are over.
     * Zero means the chunk is unremarkable and should not be reported at all.
     */
    private double score(Candidate c) {
        double score = 0.0;

        // NOT_MEASURED is negative, so it can never clear a positive threshold.
        score += ratioIfOver(c.entityCount(), thresholds.getHotspotEntitiesPerChunk());
        score += ratioIfOver(c.hopperCount(), thresholds.getHotspotHoppersPerChunk());
        score += ratioIfOver(c.blockEntityCount(), thresholds.getHotspotBlockEntitiesPerChunk());
        score += ratioIfOver(c.hopperTransfers(), thresholds.getHotspotHopperTransfers());
        score += ratioIfOver(c.redstoneActivity(), thresholds.getHotspotRedstoneActivity());

        return score;
    }

    private double ratioIfOver(double measured, double threshold) {
        if (threshold <= 0 || measured < threshold) {
            return 0.0;
        }
        return measured / threshold;
    }
}
