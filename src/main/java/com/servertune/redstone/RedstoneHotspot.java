package com.servertune.redstone;

/**
 * A chunk flagged as a redstone hotspot during a reporting window.
 */
public record RedstoneHotspot(
        String worldName,
        int chunkX,
        int chunkZ,
        long pistonMoves,
        long dispenses,
        long observerUpdates,
        long redstoneChanges,
        long blockedActions
) {

    public long totalActivity() {
        return pistonMoves + dispenses + observerUpdates + redstoneChanges;
    }

    public int blockX() {
        return chunkX << 4;
    }

    public int blockZ() {
        return chunkZ << 4;
    }

    public String describe() {
        return String.format("%s [%d, %d] - %d total (pistons=%d, dispensers=%d, observers=%d, changes=%d, blocked=%d)",
                worldName, chunkX, chunkZ, totalActivity(), pistonMoves, dispenses,
                observerUpdates, redstoneChanges, blockedActions);
    }
}
