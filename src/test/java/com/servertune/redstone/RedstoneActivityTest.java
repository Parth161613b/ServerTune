package com.servertune.redstone;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedstoneActivityTest {

    private RedstoneActivity activity() {
        return new RedstoneActivity("world", 2, -3);
    }

    @Test
    void pistonCountsAccumulateWithinOneSecond() {
        RedstoneActivity activity = activity();

        // Ticks 100..109 are all inside the same 20-tick window
        for (int i = 0; i < 10; i++) {
            int count = activity.recordPistonMove(100 + i);
            assertEquals(i + 1, count, "count should accumulate within the window");
        }

        assertEquals(10, activity.getTotalPistonMoves());
    }

    @Test
    void pistonWindowRollsAfterTwentyTicks() {
        RedstoneActivity activity = activity();

        // First window opens at tick 0 (window start initialises to 0)
        activity.recordPistonMove(0);
        assertEquals(2, activity.recordPistonMove(5));
        assertEquals(3, activity.recordPistonMove(19));

        // Tick 20 is a full second later, so the window resets to 1
        assertEquals(1, activity.recordPistonMove(20));
        assertEquals(2, activity.recordPistonMove(21));

        // Lifetime total ignores windowing
        assertEquals(5, activity.getTotalPistonMoves());
    }

    @Test
    void dispenserWindowIsIndependentOfPistonWindow() {
        RedstoneActivity activity = activity();

        activity.recordPistonMove(0);
        activity.recordPistonMove(1);

        // Reading/rolling the piston window must not disturb dispenser counting
        assertEquals(1, activity.recordDispense(1));
        assertEquals(2, activity.recordDispense(2));
        assertEquals(3, activity.recordPistonMove(2));
    }

    @Test
    void cooldownBlocksUntilExpiry() {
        RedstoneActivity activity = activity();

        assertFalse(activity.isOnCooldown(100), "no cooldown by default");

        activity.startCooldown(100, 10);

        assertTrue(activity.isOnCooldown(100));
        assertTrue(activity.isOnCooldown(109));
        assertFalse(activity.isOnCooldown(110), "cooldown should expire at start + duration");
        assertFalse(activity.isOnCooldown(200));
    }

    @Test
    void zeroCooldownIsNeverApplied() {
        RedstoneActivity activity = activity();

        activity.startCooldown(100, 0);

        assertFalse(activity.isOnCooldown(100), "a zero-tick cooldown must not block anything");
    }

    @Test
    void reportWindowRollReturnsAndClears() {
        RedstoneActivity activity = activity();

        activity.recordPistonMove(10);
        activity.recordPistonMove(11);
        activity.recordDispense(12);
        activity.recordObserverUpdate(13);
        activity.recordRedstoneChange(14);
        activity.recordRedstoneChange(15);

        RedstoneActivity.Window window = activity.rollReportWindow();

        assertEquals(2, window.pistonMoves());
        assertEquals(1, window.dispenses());
        assertEquals(1, window.observerUpdates());
        assertEquals(2, window.redstoneChanges());
        assertEquals(6, window.total());

        RedstoneActivity.Window second = activity.rollReportWindow();
        assertEquals(0, second.total(), "counters should be cleared by the roll");

        // Lifetime totals survive
        assertEquals(6, activity.getLifetimeTotal());
    }

    @Test
    void idleDetectionUsesLastActivityTick() {
        RedstoneActivity activity = activity();

        assertTrue(activity.isIdle(0, 100), "a chunk with no activity is idle");

        activity.recordPistonMove(1000);

        assertFalse(activity.isIdle(1050, 100));
        assertTrue(activity.isIdle(1101, 100), "idle once past the threshold");
    }

    @Test
    void blockedActionsAreCounted() {
        RedstoneActivity activity = activity();

        assertEquals(0, activity.getBlockedActions());
        activity.recordBlocked();
        activity.recordBlocked();
        assertEquals(2, activity.getBlockedActions());
    }

    @Test
    void concurrentRecordingKeepsLifetimeTotalsExact() throws Exception {
        RedstoneActivity activity = activity();
        int threads = 8;
        int perThread = 2000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    activity.recordPistonMove(i);
                    activity.recordDispense(i);
                }
                return null;
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish");

        assertEquals((long) threads * perThread, activity.getTotalPistonMoves());
        assertEquals((long) threads * perThread, activity.getTotalDispenses());
    }
}
