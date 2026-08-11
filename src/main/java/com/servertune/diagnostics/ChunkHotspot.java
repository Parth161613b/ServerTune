package com.servertune.diagnostics;

import java.util.ArrayList;
import java.util.List;

/**
 * A chunk that stands out in the tracked counters.
 *
 * <p>Flagging a chunk here means its measurements are unusual, nothing more. The plugin
 * counts entities, block entities and events; it does not profile the tick loop, so it
 * cannot show that any chunk is responsible for a tick cost. Every renderer of this type
 * must therefore say "potential hotspot" and leave causation to the reader.
 *
 * <p>A count of {@link #NOT_MEASURED} means the tracker that would supply it is not
 * running. That is reported as "not measured" rather than as zero, because a disabled
 * tracker and an empty chunk are very different things.
 *
 * @param score ranking weight; comparable only against other hotspots from the same sweep
 */
public record ChunkHotspot(String worldName,
                           int chunkX,
                           int chunkZ,
                           int entityCount,
                           int hopperCount,
                           int blockEntityCount,
                           long hopperTransfers,
                           long redstoneActivity,
                           int playerCount,
                           long inactiveSeconds,
                           double score) {

    /** Sentinel for a counter whose tracker is not running. */
    public static final int NOT_MEASURED = -1;

    public boolean hasEntityCount() {
        return entityCount != NOT_MEASURED;
    }

    public boolean hasBlockEntityCount() {
        return blockEntityCount != NOT_MEASURED;
    }

    /** Block coordinate of the chunk's north-west corner, for /tp. */
    public int blockX() {
        return chunkX << 4;
    }

    public int blockZ() {
        return chunkZ << 4;
    }

    public String coordinates() {
        return chunkX + "," + chunkZ;
    }

    /**
     * The measured lines for this chunk, in the order they should be displayed. Only
     * counters that were actually measured and are non-zero appear.
     */
    public List<String> measurements() {
        List<String> lines = new ArrayList<>();

        if (hasEntityCount() && entityCount > 0) {
            lines.add(entityCount + " entities");
        }
        if (hopperCount > 0) {
            lines.add(hopperCount + " hoppers");
        }
        if (hasBlockEntityCount() && blockEntityCount > hopperCount) {
            lines.add(blockEntityCount + " block entities total");
        }
        if (hopperTransfers > 0) {
            lines.add(hopperTransfers + " hopper transfers in the last sampling window");
        }
        if (redstoneActivity > 0) {
            lines.add(redstoneActivity + " redstone events in the last sampling window");
        }
        if (playerCount > 0) {
            lines.add(playerCount + (playerCount == 1 ? " player present" : " players present"));
        } else if (inactiveSeconds > 0) {
            lines.add("no player activity for " + inactiveSeconds + "s");
        }

        return lines;
    }

    public static Builder builder(String worldName, int chunkX, int chunkZ) {
        return new Builder(worldName, chunkX, chunkZ);
    }

    public static final class Builder {
        private final String worldName;
        private final int chunkX;
        private final int chunkZ;
        private int entityCount = NOT_MEASURED;
        private int hopperCount;
        private int blockEntityCount = NOT_MEASURED;
        private long hopperTransfers;
        private long redstoneActivity;
        private int playerCount;
        private long inactiveSeconds;
        private double score;

        private Builder(String worldName, int chunkX, int chunkZ) {
            this.worldName = worldName;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        public Builder entityCount(int v) {
            this.entityCount = v;
            return this;
        }

        public Builder hopperCount(int v) {
            this.hopperCount = v;
            return this;
        }

        public Builder blockEntityCount(int v) {
            this.blockEntityCount = v;
            return this;
        }

        public Builder hopperTransfers(long v) {
            this.hopperTransfers = v;
            return this;
        }

        public Builder redstoneActivity(long v) {
            this.redstoneActivity = v;
            return this;
        }

        public Builder playerCount(int v) {
            this.playerCount = v;
            return this;
        }

        public Builder inactiveSeconds(long v) {
            this.inactiveSeconds = v;
            return this;
        }

        public Builder score(double v) {
            this.score = v;
            return this;
        }

        public ChunkHotspot build() {
            return new ChunkHotspot(worldName, chunkX, chunkZ, entityCount, hopperCount,
                    blockEntityCount, hopperTransfers, redstoneActivity, playerCount,
                    inactiveSeconds, score);
        }
    }
}
