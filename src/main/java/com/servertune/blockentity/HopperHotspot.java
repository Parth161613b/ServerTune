package com.servertune.blockentity;

/**
 * A chunk flagged as a hopper hotspot, by hopper concentration or transfer volume.
 */
public record HopperHotspot(
        String worldName,
        int chunkX,
        int chunkZ,
        int hopperCount,
        long transfersPerWindow,
        Reason reason
) {

    public enum Reason {
        /** Hopper count in one chunk exceeded the concentration threshold. */
        CONCENTRATION,
        /** Observed transfers in the sampling window exceeded the activity threshold. */
        TRANSFER_VOLUME,
        /** Both thresholds exceeded. */
        BOTH
    }

    public String describe() {
        return String.format("%s [%d, %d] - %d hoppers, %d transfers/window (%s)",
                worldName, chunkX, chunkZ, hopperCount, transfersPerWindow, reason);
    }

    /** Block coordinates of the chunk's northwest corner, for teleporting to the hotspot. */
    public int blockX() {
        return chunkX << 4;
    }

    public int blockZ() {
        return chunkZ << 4;
    }
}
