package com.servertune.diagnostics;

/**
 * Every number the diagnostics and recommendation engines compare against, in one place.
 *
 * <p>Bukkit-free so the engines stay unit testable, and loaded from config.yml by
 * {@link DiagnosticsConfigLoader} at runtime.
 *
 * <p>A note on the MSPT values, because they look high next to {@code alerts.mspt.*}:
 * 50ms IS one tick, so MSPT 50 is exactly 20 TPS. Alert thresholds (40/50ms) are meant to
 * fire early and often; using them to judge server health would report a perfectly healthy
 * server as degraded. These mirror the TPS ladder instead - 1000/18 = 55.6, 1000/15 = 66.7 -
 * and match the performance guard's state thresholds.
 */
public final class DiagnosticThresholds {

    private final double tpsWarning;
    private final double tpsCritical;
    private final double tpsSevere;
    private final double msptWarning;
    private final double msptCritical;
    private final double cpuWarning;
    private final double cpuCritical;
    private final double memoryWarningPercent;
    private final double memoryCriticalPercent;
    private final int loadedChunksWarning;
    private final int loadedChunksCritical;
    private final int chunkLoadRateWarning;
    private final int chunkLoadRateCritical;
    private final int entitiesWarning;
    private final int entitiesCritical;
    private final int itemsWarning;
    private final int itemsCritical;
    private final int xpOrbsWarning;
    private final int mobsWarning;
    private final int villagersWarning;
    private final int hoppersWarning;
    private final int blockEntitiesWarning;
    private final int inactiveChunksWarning;
    private final int hotspotEntitiesPerChunk;
    private final int hotspotHoppersPerChunk;
    private final int hotspotBlockEntitiesPerChunk;
    private final long hotspotHopperTransfers;
    private final long hotspotRedstoneActivity;
    private final int maxHotspotsReported;
    private final int staleAfterSeconds;

    private DiagnosticThresholds(Builder b) {
        this.tpsWarning = b.tpsWarning;
        this.tpsCritical = b.tpsCritical;
        this.tpsSevere = b.tpsSevere;
        this.msptWarning = b.msptWarning;
        this.msptCritical = b.msptCritical;
        this.cpuWarning = b.cpuWarning;
        this.cpuCritical = b.cpuCritical;
        this.memoryWarningPercent = b.memoryWarningPercent;
        this.memoryCriticalPercent = b.memoryCriticalPercent;
        this.loadedChunksWarning = b.loadedChunksWarning;
        this.loadedChunksCritical = b.loadedChunksCritical;
        this.chunkLoadRateWarning = b.chunkLoadRateWarning;
        this.chunkLoadRateCritical = b.chunkLoadRateCritical;
        this.entitiesWarning = b.entitiesWarning;
        this.entitiesCritical = b.entitiesCritical;
        this.itemsWarning = b.itemsWarning;
        this.itemsCritical = b.itemsCritical;
        this.xpOrbsWarning = b.xpOrbsWarning;
        this.mobsWarning = b.mobsWarning;
        this.villagersWarning = b.villagersWarning;
        this.hoppersWarning = b.hoppersWarning;
        this.blockEntitiesWarning = b.blockEntitiesWarning;
        this.inactiveChunksWarning = b.inactiveChunksWarning;
        this.hotspotEntitiesPerChunk = b.hotspotEntitiesPerChunk;
        this.hotspotHoppersPerChunk = b.hotspotHoppersPerChunk;
        this.hotspotBlockEntitiesPerChunk = b.hotspotBlockEntitiesPerChunk;
        this.hotspotHopperTransfers = b.hotspotHopperTransfers;
        this.hotspotRedstoneActivity = b.hotspotRedstoneActivity;
        this.maxHotspotsReported = b.maxHotspotsReported;
        this.staleAfterSeconds = b.staleAfterSeconds;
    }

    public double getTpsWarning() {
        return tpsWarning;
    }

    public double getTpsCritical() {
        return tpsCritical;
    }

    public double getTpsSevere() {
        return tpsSevere;
    }

    public double getMsptWarning() {
        return msptWarning;
    }

    public double getMsptCritical() {
        return msptCritical;
    }

    public double getCpuWarning() {
        return cpuWarning;
    }

    public double getCpuCritical() {
        return cpuCritical;
    }

    public double getMemoryWarningPercent() {
        return memoryWarningPercent;
    }

    public double getMemoryCriticalPercent() {
        return memoryCriticalPercent;
    }

    public int getLoadedChunksWarning() {
        return loadedChunksWarning;
    }

    public int getLoadedChunksCritical() {
        return loadedChunksCritical;
    }

    public int getChunkLoadRateWarning() {
        return chunkLoadRateWarning;
    }

    public int getChunkLoadRateCritical() {
        return chunkLoadRateCritical;
    }

    public int getEntitiesWarning() {
        return entitiesWarning;
    }

    public int getEntitiesCritical() {
        return entitiesCritical;
    }

    public int getItemsWarning() {
        return itemsWarning;
    }

    public int getItemsCritical() {
        return itemsCritical;
    }

    public int getXpOrbsWarning() {
        return xpOrbsWarning;
    }

    public int getMobsWarning() {
        return mobsWarning;
    }

    public int getVillagersWarning() {
        return villagersWarning;
    }

    public int getHoppersWarning() {
        return hoppersWarning;
    }

    public int getBlockEntitiesWarning() {
        return blockEntitiesWarning;
    }

    public int getInactiveChunksWarning() {
        return inactiveChunksWarning;
    }

    public int getHotspotEntitiesPerChunk() {
        return hotspotEntitiesPerChunk;
    }

    public int getHotspotHoppersPerChunk() {
        return hotspotHoppersPerChunk;
    }

    public int getHotspotBlockEntitiesPerChunk() {
        return hotspotBlockEntitiesPerChunk;
    }

    public long getHotspotHopperTransfers() {
        return hotspotHopperTransfers;
    }

    public long getHotspotRedstoneActivity() {
        return hotspotRedstoneActivity;
    }

    public int getMaxHotspotsReported() {
        return maxHotspotsReported;
    }

    public int getStaleAfterSeconds() {
        return staleAfterSeconds;
    }

    public long getStaleAfterMillis() {
        return staleAfterSeconds * 1000L;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Config defaults, matching the shipped config.yml. */
    public static DiagnosticThresholds defaults() {
        return builder().build();
    }

    public static final class Builder {
        private double tpsWarning = 18.0;
        private double tpsCritical = 15.0;
        private double tpsSevere = 10.0;
        // Mirrors the TPS ladder; see the class comment for why these are not alerts.mspt.*
        private double msptWarning = 55.0;
        private double msptCritical = 70.0;
        private double cpuWarning = 80.0;
        private double cpuCritical = 95.0;
        private double memoryWarningPercent = 75.0;
        private double memoryCriticalPercent = 90.0;
        private int loadedChunksWarning = 3000;
        private int loadedChunksCritical = 5000;
        private int chunkLoadRateWarning = 50;
        private int chunkLoadRateCritical = 150;
        private int entitiesWarning = 3000;
        private int entitiesCritical = 8000;
        private int itemsWarning = 500;
        private int itemsCritical = 2000;
        private int xpOrbsWarning = 300;
        private int mobsWarning = 2000;
        private int villagersWarning = 200;
        private int hoppersWarning = 2000;
        private int blockEntitiesWarning = 8000;
        private int inactiveChunksWarning = 500;
        private int hotspotEntitiesPerChunk = 50;
        private int hotspotHoppersPerChunk = 24;
        private int hotspotBlockEntitiesPerChunk = 100;
        private long hotspotHopperTransfers = 500;
        private long hotspotRedstoneActivity = 600;
        private int maxHotspotsReported = 5;
        private int staleAfterSeconds = 30;

        public Builder tpsWarning(double v) {
            this.tpsWarning = v;
            return this;
        }

        public Builder tpsCritical(double v) {
            this.tpsCritical = v;
            return this;
        }

        public Builder tpsSevere(double v) {
            this.tpsSevere = v;
            return this;
        }

        public Builder msptWarning(double v) {
            this.msptWarning = v;
            return this;
        }

        public Builder msptCritical(double v) {
            this.msptCritical = v;
            return this;
        }

        public Builder cpuWarning(double v) {
            this.cpuWarning = v;
            return this;
        }

        public Builder cpuCritical(double v) {
            this.cpuCritical = v;
            return this;
        }

        public Builder memoryWarningPercent(double v) {
            this.memoryWarningPercent = v;
            return this;
        }

        public Builder memoryCriticalPercent(double v) {
            this.memoryCriticalPercent = v;
            return this;
        }

        public Builder loadedChunksWarning(int v) {
            this.loadedChunksWarning = v;
            return this;
        }

        public Builder loadedChunksCritical(int v) {
            this.loadedChunksCritical = v;
            return this;
        }

        public Builder chunkLoadRateWarning(int v) {
            this.chunkLoadRateWarning = v;
            return this;
        }

        public Builder chunkLoadRateCritical(int v) {
            this.chunkLoadRateCritical = v;
            return this;
        }

        public Builder entitiesWarning(int v) {
            this.entitiesWarning = v;
            return this;
        }

        public Builder entitiesCritical(int v) {
            this.entitiesCritical = v;
            return this;
        }

        public Builder itemsWarning(int v) {
            this.itemsWarning = v;
            return this;
        }

        public Builder itemsCritical(int v) {
            this.itemsCritical = v;
            return this;
        }

        public Builder xpOrbsWarning(int v) {
            this.xpOrbsWarning = v;
            return this;
        }

        public Builder mobsWarning(int v) {
            this.mobsWarning = v;
            return this;
        }

        public Builder villagersWarning(int v) {
            this.villagersWarning = v;
            return this;
        }

        public Builder hoppersWarning(int v) {
            this.hoppersWarning = v;
            return this;
        }

        public Builder blockEntitiesWarning(int v) {
            this.blockEntitiesWarning = v;
            return this;
        }

        public Builder inactiveChunksWarning(int v) {
            this.inactiveChunksWarning = v;
            return this;
        }

        public Builder hotspotEntitiesPerChunk(int v) {
            this.hotspotEntitiesPerChunk = v;
            return this;
        }

        public Builder hotspotHoppersPerChunk(int v) {
            this.hotspotHoppersPerChunk = v;
            return this;
        }

        public Builder hotspotBlockEntitiesPerChunk(int v) {
            this.hotspotBlockEntitiesPerChunk = v;
            return this;
        }

        public Builder hotspotHopperTransfers(long v) {
            this.hotspotHopperTransfers = v;
            return this;
        }

        public Builder hotspotRedstoneActivity(long v) {
            this.hotspotRedstoneActivity = v;
            return this;
        }

        public Builder maxHotspotsReported(int v) {
            this.maxHotspotsReported = v;
            return this;
        }

        public Builder staleAfterSeconds(int v) {
            this.staleAfterSeconds = v;
            return this;
        }

        public DiagnosticThresholds build() {
            return new DiagnosticThresholds(this);
        }
    }
}
