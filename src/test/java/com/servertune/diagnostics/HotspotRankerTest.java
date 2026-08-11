package com.servertune.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 2: identify potential hotspots, and do not claim causation. The ranker decides what
 * is worth listing; these tests pin down that an unremarkable chunk is never listed and that
 * an unmeasured counter is never treated as a count.
 */
class HotspotRankerTest {

    private final DiagnosticThresholds thresholds = DiagnosticThresholds.defaults();
    private final HotspotRanker ranker = new HotspotRanker(thresholds);

    private static HotspotRanker.Candidate candidate(int chunkX, int chunkZ, int entities,
                                                     int hoppers) {
        return new HotspotRanker.Candidate("world", chunkX, chunkZ, entities, hoppers,
                ChunkHotspot.NOT_MEASURED, 0L, 0L, 0, 0L);
    }

    @Test
    void chunkUnderEveryThresholdIsNotReported() {
        List<ChunkHotspot> ranked = ranker.rank(List.of(candidate(0, 0, 10, 2)));

        assertTrue(ranked.isEmpty(), "an ordinary chunk must not be listed as a hotspot");
    }

    @Test
    void chunkOverOneThresholdIsReported() {
        List<ChunkHotspot> ranked = ranker.rank(List.of(candidate(124, -37, 82, 0)));

        assertEquals(1, ranked.size());
        ChunkHotspot h = ranked.get(0);
        assertEquals("124,-37", h.coordinates());
        assertEquals(82, h.entityCount());
        assertTrue(h.score() > 0.0);
    }

    /**
     * NOT_MEASURED is -1. It must never clear a positive threshold, or a disabled tracker
     * would manufacture hotspots.
     */
    @Test
    void unmeasuredCountersNeverScore() {
        HotspotRanker.Candidate blind = new HotspotRanker.Candidate("world", 5, 5,
                ChunkHotspot.NOT_MEASURED, 0, ChunkHotspot.NOT_MEASURED, 0L, 0L, 0, 0L);

        assertTrue(ranker.rank(List.of(blind)).isEmpty());
    }

    @Test
    void unmeasuredEntityCountIsRenderedAsAbsentNotZero() {
        ChunkHotspot h = ranker.rank(List.of(new HotspotRanker.Candidate("world", 1, 1,
                ChunkHotspot.NOT_MEASURED, 40, ChunkHotspot.NOT_MEASURED, 0L, 0L, 0, 0L)))
                .get(0);

        assertFalse(h.hasEntityCount());
        assertTrue(h.measurements().stream().noneMatch(line -> line.contains("entities")),
                "a count that was never taken must not appear at all");
        assertTrue(h.measurements().contains("40 hoppers"));
    }

    @Test
    void higherScoringChunkSortsFirst() {
        List<ChunkHotspot> ranked = ranker.rank(List.of(
                candidate(1, 1, 60, 0),      // just over the entity threshold
                candidate(2, 2, 300, 100)    // far over two thresholds
        ));

        assertEquals(2, ranked.size());
        assertEquals("2,2", ranked.get(0).coordinates());
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
    }

    @Test
    void reportIsTruncatedToMaxReported() {
        List<HotspotRanker.Candidate> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(candidate(i, i, 100 + i, 0));
        }

        List<ChunkHotspot> ranked = ranker.rank(many);

        assertEquals(thresholds.getMaxHotspotsReported(), ranked.size());
        // Truncation keeps the worst, not the first seen.
        assertEquals("19,19", ranked.get(0).coordinates());
    }

    /**
     * The score is a ratio-of-threshold sum. Two counters at twice their threshold score 4.
     * Documented here so nobody later mistakes it for a millisecond cost.
     */
    @Test
    void scoreIsTheSumOfThresholdRatios() {
        int entities = thresholds.getHotspotEntitiesPerChunk() * 2;
        int hoppers = thresholds.getHotspotHoppersPerChunk() * 2;

        ChunkHotspot h = ranker.rank(List.of(candidate(0, 0, entities, hoppers))).get(0);

        assertEquals(4.0, h.score(), 0.0001);
    }

    @Test
    void emptyCandidateListYieldsNoHotspots() {
        assertTrue(ranker.rank(List.of()).isEmpty());
    }

    @Test
    void blockCoordinatesPointAtTheChunkCorner() {
        ChunkHotspot h = ranker.rank(List.of(candidate(124, -37, 82, 0))).get(0);

        assertEquals(1984, h.blockX());
        assertEquals(-592, h.blockZ());
    }

    @Test
    void inactivityIsReportedWhenNoPlayerIsPresent() {
        ChunkHotspot h = ranker.rank(List.of(new HotspotRanker.Candidate("world", 3, 3,
                90, 0, ChunkHotspot.NOT_MEASURED, 0L, 0L, 0, 420L))).get(0);

        assertTrue(h.measurements().contains("no player activity for 420s"));
    }
}
