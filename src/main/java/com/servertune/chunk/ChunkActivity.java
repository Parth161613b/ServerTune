package com.servertune.chunk;

import org.bukkit.Chunk;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight chunk activity tracking model.
 * Tracks player activity, entity counts, and protected state without expensive recalculations.
 */
public class ChunkActivity {

    private final int chunkX;
    private final int chunkZ;
    private final String worldName;

    // Timestamps
    private volatile long lastPlayerActivity;
    private volatile long lastEntityCheck;
    private volatile long loadTime;

    // Cached counts (updated periodically, not every tick)
    private final AtomicInteger playerCount;
    private volatile int entityCount;
    private volatile int tileEntityCount;

    // Activity flags
    private volatile boolean hasHoppers;
    private volatile boolean hasRedstone;
    private volatile boolean isProtected;
    private volatile boolean isForcedLoaded;

    public ChunkActivity(Chunk chunk) {
        this.chunkX = chunk.getX();
        this.chunkZ = chunk.getZ();
        this.worldName = chunk.getWorld().getName();
        this.loadTime = System.currentTimeMillis();
        this.lastPlayerActivity = System.currentTimeMillis();
        this.lastEntityCheck = 0;
        this.playerCount = new AtomicInteger(0);
        this.entityCount = 0;
        this.tileEntityCount = 0;
        this.hasHoppers = false;
        this.hasRedstone = false;
        this.isProtected = false;
        this.isForcedLoaded = false;
    }

    public void updatePlayerActivity() {
        this.lastPlayerActivity = System.currentTimeMillis();
    }

    public void setPlayerCount(int count) {
        this.playerCount.set(count);
        if (count > 0) {
            updatePlayerActivity();
        }
    }

    public int incrementPlayerCount() {
        int newCount = this.playerCount.incrementAndGet();
        updatePlayerActivity();
        return newCount;
    }

    public int decrementPlayerCount() {
        return this.playerCount.decrementAndGet();
    }

    public void updateEntityCounts(int entities, int tileEntities) {
        this.entityCount = entities;
        this.tileEntityCount = tileEntities;
        this.lastEntityCheck = System.currentTimeMillis();
    }

    public long getInactiveMillis() {
        return System.currentTimeMillis() - lastPlayerActivity;
    }

    public long getInactiveSeconds() {
        return getInactiveMillis() / 1000;
    }

    public boolean isInactive(long thresholdMillis) {
        return getInactiveMillis() > thresholdMillis;
    }

    public boolean hasPlayers() {
        return playerCount.get() > 0;
    }

    // Getters
    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    public String getWorldName() {
        return worldName;
    }

    public long getLastPlayerActivity() {
        return lastPlayerActivity;
    }

    public long getLoadTime() {
        return loadTime;
    }

    public int getPlayerCount() {
        return playerCount.get();
    }

    public int getEntityCount() {
        return entityCount;
    }

    public int getTileEntityCount() {
        return tileEntityCount;
    }

    public boolean hasHoppers() {
        return hasHoppers;
    }

    public void setHasHoppers(boolean hasHoppers) {
        this.hasHoppers = hasHoppers;
    }

    public boolean hasRedstone() {
        return hasRedstone;
    }

    public void setHasRedstone(boolean hasRedstone) {
        this.hasRedstone = hasRedstone;
    }

    public boolean isProtected() {
        return isProtected;
    }

    public void setProtected(boolean isProtected) {
        this.isProtected = isProtected;
    }

    public boolean isForcedLoaded() {
        return isForcedLoaded;
    }

    public void setForcedLoaded(boolean forcedLoaded) {
        isForcedLoaded = forcedLoaded;
    }

    public long getChunkKey() {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public String toString() {
        return String.format("ChunkActivity{world=%s, x=%d, z=%d, players=%d, inactive=%ds}",
                worldName, chunkX, chunkZ, playerCount.get(), getInactiveSeconds());
    }
}
