package com.servertune.redstone;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmarks the two hot paths in the redstone module.
 *
 * <p>Spec section 8: "The redstone monitor itself must not become a redstone lag source."
 * The per-event path runs on every piston move and redstone change, so it is the number
 * that actually matters.
 */
class RedstoneBenchmarkTest {

    private static final int EVENTS = 1_000_000;
    private static final int CHUNKS = 20_000;

    /** Mirrors RedstoneOptimizationModule#activityFor plus the counter update. */
    private RedstoneActivity activityFor(Map<Long, RedstoneActivity> world, int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        return world.computeIfAbsent(key, k -> new RedstoneActivity("world", chunkX, chunkZ));
    }

    @Test
    void perEventOverheadIsNegligible() {
        Map<Long, RedstoneActivity> world = new ConcurrentHashMap<>();

        // Warm up
        for (int i = 0; i < 100_000; i++) {
            activityFor(world, i % 64, i % 64).recordPistonMove(i / 4);
        }
        world.clear();

        long startNanos = System.nanoTime();
        for (int i = 0; i < EVENTS; i++) {
            // Spread across 4096 chunks, one piston move each per simulated tick
            activityFor(world, i % 64, (i / 64) % 64).recordPistonMove(i / 4);
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        double nanosPerEvent = (double) elapsedNanos / EVENTS;
        double millisTotal = elapsedNanos / 1_000_000.0;

        System.out.printf("Redstone per-event path: %,d events in %.1f ms = %.1f ns/event%n",
                EVENTS, millisTotal, nanosPerEvent);

        // A busy server might see a few thousand redstone events per tick. At this cost,
        // 5000 events/tick would be well under a millisecond of the 50ms budget.
        assertTrue(nanosPerEvent < 1000.0,
                String.format("per-event cost was %.1f ns, expected under 1000 ns", nanosPerEvent));
    }

    @Test
    void reportingCycleStaysWellUnderOneTick() {
        Map<Long, RedstoneActivity> world = new ConcurrentHashMap<>();

        for (int i = 0; i < CHUNKS; i++) {
            RedstoneActivity activity = activityFor(world, i % 200, i / 200);
            int volume = i % 100 == 0 ? 900 : i % 15;
            for (int v = 0; v < volume; v++) {
                activity.recordPistonMove(v);
            }
        }

        long startNanos = System.nanoTime();

        List<RedstoneHotspot> hotspots = new ArrayList<>();
        for (RedstoneActivity activity : world.values()) {
            RedstoneActivity.Window window = activity.rollReportWindow();
            if (window.total() < 600) {
                continue;
            }
            hotspots.add(new RedstoneHotspot(activity.getWorldName(), activity.getChunkX(),
                    activity.getChunkZ(), window.pistonMoves(), window.dispenses(),
                    window.observerUpdates(), window.redstoneChanges(), activity.getBlockedActions()));
        }
        hotspots.sort(Comparator.comparingLong(RedstoneHotspot::totalActivity).reversed());

        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedMillis = elapsedNanos / 1_000_000.0;

        System.out.printf("Redstone reporting cycle over %,d chunks: %.3f ms (%d hotspots)%n",
                CHUNKS, elapsedMillis, hotspots.size());

        assertTrue(elapsedMillis < 25.0,
                String.format("reporting cycle took %.3f ms, expected under 25 ms", elapsedMillis));
        assertTrue(hotspots.size() > 0, "fixture should produce hotspots");
    }

    @Test
    void rateLimitDecisionMatchesConfiguredCap() {
        // Simulates the enforcement branch: 100 piston moves in one tick, cap of 10
        RedstoneActivity activity = new RedstoneActivity("world", 0, 0);
        int cap = 10;
        int allowed = 0;
        int blocked = 0;

        for (int i = 0; i < 100; i++) {
            int thisSecond = activity.recordPistonMove(50);
            if (thisSecond > cap) {
                blocked++;
                activity.recordBlocked();
            } else {
                allowed++;
            }
        }

        assertEquals(cap, allowed, "exactly the cap should pass within one window");
        assertEquals(100 - cap, blocked);
        assertEquals(blocked, activity.getBlockedActions());

        // Next second, the budget refreshes
        assertEquals(1, activity.recordPistonMove(70));
    }
}
