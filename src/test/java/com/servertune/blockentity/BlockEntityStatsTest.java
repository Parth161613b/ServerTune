package com.servertune.blockentity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityStatsTest {

    private BlockEntityStats stats() {
        return new BlockEntityStats("world", 4, -7);
    }

    @Test
    void setCountsPopulatesTotals() {
        BlockEntityStats stats = stats();
        stats.setCounts(10, 3, 1, 6);

        assertEquals(10, stats.getHopperCount());
        assertEquals(3, stats.getFurnaceCount());
        assertEquals(1, stats.getBrewingStandCount());
        assertEquals(6, stats.getOtherCount());
        assertEquals(20, stats.getTotalBlockEntities());
    }

    @Test
    void adjustmentsTrackPlaceAndBreak() {
        BlockEntityStats stats = stats();
        stats.setCounts(5, 0, 0, 0);

        assertEquals(6, stats.adjustHoppers(1));
        assertEquals(5, stats.adjustHoppers(-1));
    }

    @Test
    void countsNeverGoNegative() {
        BlockEntityStats stats = stats();

        // A break event for a block placed before the plugin loaded would otherwise underflow
        assertEquals(0, stats.adjustHoppers(-3));
        assertEquals(0, stats.getHopperCount());
        assertEquals(0, stats.adjustFurnaces(-1));
        assertEquals(0, stats.adjustBrewingStands(-1));
        assertEquals(0, stats.adjustOther(-1));
    }

    @Test
    void windowResetReturnsCountAndClears() {
        BlockEntityStats stats = stats();

        stats.recordTransfer(100);
        stats.recordTransfer(104);
        stats.recordTransfer(112);

        assertEquals(3, stats.getTransfersThisWindow());
        assertEquals(3, stats.getTotalTransfers());
        assertEquals(112, stats.getLastTransferTick());

        assertEquals(3, stats.resetWindow());
        assertEquals(0, stats.getTransfersThisWindow());

        // Lifetime total survives the window roll
        assertEquals(3, stats.getTotalTransfers());
    }

    @Test
    void lastTransferTickStartsUnset() {
        assertEquals(-1, stats().getLastTransferTick());
    }

    @Test
    void scanStalenessReflectsLastScan() {
        BlockEntityStats stats = stats();

        // Never scanned, so any age threshold is stale
        assertTrue(stats.isScanStale(1000));

        stats.setCounts(1, 0, 0, 0);
        assertFalse(stats.isScanStale(10_000));
    }

    @Test
    void concurrentTransfersAndAdjustmentsAreConsistent() throws Exception {
        BlockEntityStats stats = stats();
        int threads = 8;
        int perThread = 1000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    stats.recordTransfer(i);
                    stats.adjustHoppers(1);
                }
                return null;
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish");

        assertEquals(threads * perThread, stats.getTotalTransfers());
        assertEquals(threads * perThread, stats.getHopperCount());
    }
}
