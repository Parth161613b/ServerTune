package com.servertune.blockentity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmarks the hotspot pass the hopper module runs each cycle.
 *
 * This mirrors HopperOptimizationModule#optimize: roll each chunk's transfer window and
 * compare cached counts against thresholds. No block or world access, which is the point.
 */
class HotspotScanBenchmarkTest {

    private static final int CHUNKS = 20_000;
    private static final int HOPPER_THRESHOLD = 24;
    private static final int TRANSFER_THRESHOLD = 500;

    private Map<Long, BlockEntityStats> buildWorld(int chunkCount) {
        Map<Long, BlockEntityStats> world = new ConcurrentHashMap<>();

        for (int i = 0; i < chunkCount; i++) {
            int x = i % 200;
            int z = i / 200;
            BlockEntityStats stats = new BlockEntityStats("world", x, z);

            // Every 50th chunk is a hopper-dense hotspot
            int hoppers = i % 50 == 0 ? 40 : i % 7;
            stats.setCounts(hoppers, i % 3, i % 11 == 0 ? 1 : 0, i % 5);

            // Every 100th chunk is transfer-busy
            int transfers = i % 100 == 0 ? 800 : i % 20;
            for (int t = 0; t < transfers; t++) {
                stats.recordTransfer(t);
            }

            world.put(((long) x << 32) | (z & 0xFFFFFFFFL), stats);
        }

        return world;
    }

    private List<HopperHotspot> hotspotPass(Map<Long, BlockEntityStats> world) {
        List<HopperHotspot> found = new ArrayList<>();

        for (BlockEntityStats stats : world.values()) {
            long windowTransfers = stats.resetWindow();

            boolean concentrated = stats.getHopperCount() >= HOPPER_THRESHOLD;
            boolean busy = windowTransfers >= TRANSFER_THRESHOLD;

            if (!concentrated && !busy) {
                continue;
            }

            HopperHotspot.Reason reason = concentrated && busy
                    ? HopperHotspot.Reason.BOTH
                    : concentrated ? HopperHotspot.Reason.CONCENTRATION
                    : HopperHotspot.Reason.TRANSFER_VOLUME;

            found.add(new HopperHotspot(stats.getWorldName(), stats.getChunkX(), stats.getChunkZ(),
                    stats.getHopperCount(), windowTransfers, reason));
        }

        found.sort(Comparator.comparingLong(HopperHotspot::transfersPerWindow).reversed());
        return found;
    }

    @Test
    void hotspotPassStaysWellUnderOneTick() {
        Map<Long, BlockEntityStats> world = buildWorld(CHUNKS);

        // Warm up so we are not timing class loading and JIT
        for (int i = 0; i < 5; i++) {
            hotspotPass(buildWorld(1000));
        }

        long startNanos = System.nanoTime();
        List<HopperHotspot> hotspots = hotspotPass(world);
        long elapsedNanos = System.nanoTime() - startNanos;

        double elapsedMillis = elapsedNanos / 1_000_000.0;

        System.out.printf("Hotspot pass over %,d chunks: %.3f ms (%d hotspots)%n",
                CHUNKS, elapsedMillis, hotspots.size());

        // A tick is 50ms. This pass runs every 400 ticks and must not be a spike source.
        assertTrue(elapsedMillis < 25.0,
                String.format("hotspot pass took %.3f ms over %d chunks, expected under 25 ms",
                        elapsedMillis, CHUNKS));
        assertTrue(hotspots.size() > 0, "fixture should produce hotspots");
    }

    @Test
    void hotspotPassIdentifiesBothReasons() {
        // Chunk 0 is both dense (40 hoppers) and busy (800 transfers)
        List<HopperHotspot> hotspots = hotspotPass(buildWorld(300));

        assertTrue(hotspots.stream().anyMatch(h -> h.reason() == HopperHotspot.Reason.BOTH),
                "expected a chunk flagged for both concentration and volume");
        assertTrue(hotspots.stream().anyMatch(h -> h.reason() == HopperHotspot.Reason.CONCENTRATION),
                "expected a concentration-only chunk");

        // Busiest first
        for (int i = 1; i < hotspots.size(); i++) {
            assertTrue(hotspots.get(i - 1).transfersPerWindow() >= hotspots.get(i).transfersPerWindow(),
                    "hotspots should be sorted by transfer volume descending");
        }
    }

    @Test
    void windowRollLeavesCountsClearForNextCycle() {
        Map<Long, BlockEntityStats> world = buildWorld(500);

        assertTrue(hotspotPass(world).stream()
                        .anyMatch(h -> h.reason() != HopperHotspot.Reason.CONCENTRATION),
                "first pass should see transfer volume");

        // Second pass with no new transfers: only concentration should remain
        List<HopperHotspot> second = hotspotPass(world);
        assertEquals(0, second.stream()
                .filter(h -> h.reason() != HopperHotspot.Reason.CONCENTRATION)
                .count(), "transfer windows should have been cleared by the first pass");
    }

    @Test
    void hotspotExposesChunkBlockCoordinates() {
        HopperHotspot hotspot = new HopperHotspot("world", 3, -2, 40, 900,
                HopperHotspot.Reason.BOTH);

        assertEquals(48, hotspot.blockX());
        assertEquals(-32, hotspot.blockZ());
        assertTrue(hotspot.describe().contains("40 hoppers"));
    }
}
