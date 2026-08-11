package com.servertune.blockentity;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-chunk block entity counts and hopper activity.
 * Counts are maintained incrementally from events; a bounded rescan corrects drift.
 */
public class BlockEntityStats {

    private final String worldName;
    private final int chunkX;
    private final int chunkZ;

    private final AtomicInteger hopperCount;
    private final AtomicInteger furnaceCount;
    private final AtomicInteger brewingStandCount;
    private final AtomicInteger otherCount;

    // Hopper transfer activity observed through events
    private final AtomicLong totalTransfers;
    private final AtomicLong transfersThisWindow;
    private volatile int lastTransferTick;

    private volatile long lastScan;

    public BlockEntityStats(String worldName, int chunkX, int chunkZ) {
        this.worldName = worldName;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.hopperCount = new AtomicInteger(0);
        this.furnaceCount = new AtomicInteger(0);
        this.brewingStandCount = new AtomicInteger(0);
        this.otherCount = new AtomicInteger(0);
        this.totalTransfers = new AtomicLong(0);
        this.transfersThisWindow = new AtomicLong(0);
        this.lastTransferTick = -1;
        this.lastScan = 0L;
    }

    public void setCounts(int hoppers, int furnaces, int brewingStands, int other) {
        this.hopperCount.set(hoppers);
        this.furnaceCount.set(furnaces);
        this.brewingStandCount.set(brewingStands);
        this.otherCount.set(other);
        this.lastScan = System.currentTimeMillis();
    }

    public void recordTransfer(int currentTick) {
        this.totalTransfers.incrementAndGet();
        this.transfersThisWindow.incrementAndGet();
        this.lastTransferTick = currentTick;
    }

    public long resetWindow() {
        return transfersThisWindow.getAndSet(0);
    }

    public int adjustHoppers(int delta) {
        return clampToZero(hopperCount, delta);
    }

    public int adjustFurnaces(int delta) {
        return clampToZero(furnaceCount, delta);
    }

    public int adjustBrewingStands(int delta) {
        return clampToZero(brewingStandCount, delta);
    }

    public int adjustOther(int delta) {
        return clampToZero(otherCount, delta);
    }

    private int clampToZero(AtomicInteger counter, int delta) {
        int updated = counter.addAndGet(delta);
        if (updated < 0) {
            counter.set(0);
            return 0;
        }
        return updated;
    }

    public int getTotalBlockEntities() {
        return hopperCount.get() + furnaceCount.get() + brewingStandCount.get() + otherCount.get();
    }

    public boolean isScanStale(long maxAgeMillis) {
        return System.currentTimeMillis() - lastScan > maxAgeMillis;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public int getHopperCount() {
        return hopperCount.get();
    }

    public int getFurnaceCount() {
        return furnaceCount.get();
    }

    public int getBrewingStandCount() {
        return brewingStandCount.get();
    }

    public int getOtherCount() {
        return otherCount.get();
    }

    public long getTotalTransfers() {
        return totalTransfers.get();
    }

    public long getTransfersThisWindow() {
        return transfersThisWindow.get();
    }

    public int getLastTransferTick() {
        return lastTransferTick;
    }

    public long getLastScan() {
        return lastScan;
    }

    @Override
    public String toString() {
        return String.format("BlockEntityStats{%s %d,%d hoppers=%d furnaces=%d brewing=%d other=%d transfers=%d}",
                worldName, chunkX, chunkZ, hopperCount.get(), furnaceCount.get(),
                brewingStandCount.get(), otherCount.get(), totalTransfers.get());
    }
}
