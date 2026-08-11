package com.servertune.diagnostics;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the diagnostics and recommendation engines are allowed to look at, frozen at
 * one instant.
 *
 * <p>Deliberately free of Bukkit types. Two reasons, and both matter:
 * <ul>
 *   <li>the engines can then run on an async thread without touching a single unsafe API -
 *       every value here was already read on the main thread when the snapshot was built</li>
 *   <li>the engines can be unit tested without a server, since paper-api is compileOnly</li>
 * </ul>
 *
 * <p>Counters whose tracker is not running hold {@link #NOT_MEASURED}. Reporting "not
 * measured" instead of zero keeps the output honest: a disabled hopper module and a server
 * with no hoppers are not the same observation.
 */
public final class DiagnosticSnapshot {

    /** A counter whose tracker is not running. */
    public static final int NOT_MEASURED = -1;

    private final long sampledAt;
    private final double tps;
    private final double mspt;
    private final double cpuUsage;
    private final long memoryUsed;
    private final long memoryAllocated;
    private final long memoryMax;
    private final int onlinePlayers;
    private final int maxPlayers;
    private final int loadedChunks;
    private final int chunkLoadRate;
    private final int chunkUnloadRate;
    private final int trackedChunks;
    private final int inactiveChunks;
    private final int forceLoadedChunks;
    private final int totalEntities;
    private final int itemCount;
    private final int xpOrbCount;
    private final int mobCount;
    private final int villagerCount;
    private final int hopperCount;
    private final int blockEntityCount;
    private final long hopperTransfersPerWindow;
    private final long redstoneActivityPerWindow;
    private final Map<String, Integer> entityCounts;
    private final List<ChunkHotspot> hotspots;
    private final Set<String> enabledModules;
    private final boolean itemMergingEnabled;
    private final boolean inactiveChunkUnloadingEnabled;
    private final boolean perChunkEntityCountsMeasured;
    private final String serverStatus;
    private final String activeProfile;
    private final String guardState;

    private DiagnosticSnapshot(Builder b) {
        this.sampledAt = b.sampledAt;
        this.tps = b.tps;
        this.mspt = b.mspt;
        this.cpuUsage = b.cpuUsage;
        this.memoryUsed = b.memoryUsed;
        this.memoryAllocated = b.memoryAllocated;
        this.memoryMax = b.memoryMax;
        this.onlinePlayers = b.onlinePlayers;
        this.maxPlayers = b.maxPlayers;
        this.loadedChunks = b.loadedChunks;
        this.chunkLoadRate = b.chunkLoadRate;
        this.chunkUnloadRate = b.chunkUnloadRate;
        this.trackedChunks = b.trackedChunks;
        this.inactiveChunks = b.inactiveChunks;
        this.forceLoadedChunks = b.forceLoadedChunks;
        this.totalEntities = b.totalEntities;
        this.itemCount = b.itemCount;
        this.xpOrbCount = b.xpOrbCount;
        this.mobCount = b.mobCount;
        this.villagerCount = b.villagerCount;
        this.hopperCount = b.hopperCount;
        this.blockEntityCount = b.blockEntityCount;
        this.hopperTransfersPerWindow = b.hopperTransfersPerWindow;
        this.redstoneActivityPerWindow = b.redstoneActivityPerWindow;
        this.entityCounts = Map.copyOf(b.entityCounts);
        this.hotspots = List.copyOf(b.hotspots);
        this.enabledModules = Set.copyOf(b.enabledModules);
        this.itemMergingEnabled = b.itemMergingEnabled;
        this.inactiveChunkUnloadingEnabled = b.inactiveChunkUnloadingEnabled;
        this.perChunkEntityCountsMeasured = b.perChunkEntityCountsMeasured;
        this.serverStatus = b.serverStatus;
        this.activeProfile = b.activeProfile;
        this.guardState = b.guardState;
    }

    public long getSampledAt() {
        return sampledAt;
    }

    /** How long ago this was sampled, against a caller-supplied clock. */
    public long getAgeMillis(long now) {
        return Math.max(0L, now - sampledAt);
    }

    public double getTps() {
        return tps;
    }

    public double getMspt() {
        return mspt;
    }

    public double getCpuUsage() {
        return cpuUsage;
    }

    public long getMemoryUsed() {
        return memoryUsed;
    }

    public long getMemoryAllocated() {
        return memoryAllocated;
    }

    public long getMemoryMax() {
        return memoryMax;
    }

    /** Heap use as a percentage of max, or 0 when max is unknown. */
    public double getMemoryUsedPercent() {
        return memoryMax <= 0 ? 0.0 : (memoryUsed * 100.0) / memoryMax;
    }

    public int getOnlinePlayers() {
        return onlinePlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public int getLoadedChunks() {
        return loadedChunks;
    }

    public int getChunkLoadRate() {
        return chunkLoadRate;
    }

    public int getChunkUnloadRate() {
        return chunkUnloadRate;
    }

    public int getTrackedChunks() {
        return trackedChunks;
    }

    public int getInactiveChunks() {
        return inactiveChunks;
    }

    public int getForceLoadedChunks() {
        return forceLoadedChunks;
    }

    public int getTotalEntities() {
        return totalEntities;
    }

    public int getItemCount() {
        return itemCount;
    }

    public int getXpOrbCount() {
        return xpOrbCount;
    }

    public int getMobCount() {
        return mobCount;
    }

    public int getVillagerCount() {
        return villagerCount;
    }

    public int getHopperCount() {
        return hopperCount;
    }

    public int getBlockEntityCount() {
        return blockEntityCount;
    }

    public boolean hasHopperCount() {
        return hopperCount != NOT_MEASURED;
    }

    public boolean hasBlockEntityCount() {
        return blockEntityCount != NOT_MEASURED;
    }

    public long getHopperTransfersPerWindow() {
        return hopperTransfersPerWindow;
    }

    public long getRedstoneActivityPerWindow() {
        return redstoneActivityPerWindow;
    }

    /** Entity counts keyed by entity type name, unmodifiable. */
    public Map<String, Integer> getEntityCounts() {
        return entityCounts;
    }

    /** Ranked potential hotspots, most notable first, unmodifiable. */
    public List<ChunkHotspot> getHotspots() {
        return hotspots;
    }

    public Set<String> getEnabledModules() {
        return enabledModules;
    }

    public boolean isModuleEnabled(String moduleName) {
        return enabledModules.contains(moduleName);
    }

    public boolean isItemMergingEnabled() {
        return itemMergingEnabled;
    }

    public boolean isInactiveChunkUnloadingEnabled() {
        return inactiveChunkUnloadingEnabled;
    }

    /** False when no per-chunk entity pass has run, so hotspot entity counts are absent. */
    public boolean isPerChunkEntityCountsMeasured() {
        return perChunkEntityCountsMeasured;
    }

    public String getServerStatus() {
        return serverStatus;
    }

    public String getActiveProfile() {
        return activeProfile;
    }

    /** Current performance-guard state name, or "UNKNOWN" when the guard is off. */
    public String getGuardState() {
        return guardState;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long sampledAt;
        private double tps;
        private double mspt;
        private double cpuUsage;
        private long memoryUsed;
        private long memoryAllocated;
        private long memoryMax;
        private int onlinePlayers;
        private int maxPlayers;
        private int loadedChunks;
        private int chunkLoadRate;
        private int chunkUnloadRate;
        private int trackedChunks;
        private int inactiveChunks;
        private int forceLoadedChunks;
        private int totalEntities;
        private int itemCount;
        private int xpOrbCount;
        private int mobCount;
        private int villagerCount;
        private int hopperCount = NOT_MEASURED;
        private int blockEntityCount = NOT_MEASURED;
        private long hopperTransfersPerWindow;
        private long redstoneActivityPerWindow;
        private Map<String, Integer> entityCounts = Collections.emptyMap();
        private List<ChunkHotspot> hotspots = List.of();
        private Set<String> enabledModules = Set.of();
        private boolean itemMergingEnabled;
        private boolean inactiveChunkUnloadingEnabled;
        private boolean perChunkEntityCountsMeasured;
        private String serverStatus = "UNKNOWN";
        private String activeProfile = "unknown";
        private String guardState = "UNKNOWN";

        public Builder sampledAt(long v) {
            this.sampledAt = v;
            return this;
        }

        public Builder tps(double v) {
            this.tps = v;
            return this;
        }

        public Builder mspt(double v) {
            this.mspt = v;
            return this;
        }

        public Builder cpuUsage(double v) {
            this.cpuUsage = v;
            return this;
        }

        public Builder memoryUsed(long v) {
            this.memoryUsed = v;
            return this;
        }

        public Builder memoryAllocated(long v) {
            this.memoryAllocated = v;
            return this;
        }

        public Builder memoryMax(long v) {
            this.memoryMax = v;
            return this;
        }

        public Builder onlinePlayers(int v) {
            this.onlinePlayers = v;
            return this;
        }

        public Builder maxPlayers(int v) {
            this.maxPlayers = v;
            return this;
        }

        public Builder loadedChunks(int v) {
            this.loadedChunks = v;
            return this;
        }

        public Builder chunkLoadRate(int v) {
            this.chunkLoadRate = v;
            return this;
        }

        public Builder chunkUnloadRate(int v) {
            this.chunkUnloadRate = v;
            return this;
        }

        public Builder trackedChunks(int v) {
            this.trackedChunks = v;
            return this;
        }

        public Builder inactiveChunks(int v) {
            this.inactiveChunks = v;
            return this;
        }

        public Builder forceLoadedChunks(int v) {
            this.forceLoadedChunks = v;
            return this;
        }

        public Builder totalEntities(int v) {
            this.totalEntities = v;
            return this;
        }

        public Builder itemCount(int v) {
            this.itemCount = v;
            return this;
        }

        public Builder xpOrbCount(int v) {
            this.xpOrbCount = v;
            return this;
        }

        public Builder mobCount(int v) {
            this.mobCount = v;
            return this;
        }

        public Builder villagerCount(int v) {
            this.villagerCount = v;
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

        public Builder hopperTransfersPerWindow(long v) {
            this.hopperTransfersPerWindow = v;
            return this;
        }

        public Builder redstoneActivityPerWindow(long v) {
            this.redstoneActivityPerWindow = v;
            return this;
        }

        public Builder entityCounts(Map<String, Integer> v) {
            this.entityCounts = v == null ? Collections.emptyMap() : v;
            return this;
        }

        public Builder hotspots(List<ChunkHotspot> v) {
            this.hotspots = v == null ? List.of() : v;
            return this;
        }

        public Builder enabledModules(Set<String> v) {
            this.enabledModules = v == null ? Set.of() : v;
            return this;
        }

        public Builder itemMergingEnabled(boolean v) {
            this.itemMergingEnabled = v;
            return this;
        }

        public Builder inactiveChunkUnloadingEnabled(boolean v) {
            this.inactiveChunkUnloadingEnabled = v;
            return this;
        }

        public Builder perChunkEntityCountsMeasured(boolean v) {
            this.perChunkEntityCountsMeasured = v;
            return this;
        }

        public Builder serverStatus(String v) {
            this.serverStatus = v;
            return this;
        }

        public Builder activeProfile(String v) {
            this.activeProfile = v;
            return this;
        }

        public Builder guardState(String v) {
            this.guardState = v;
            return this;
        }

        public DiagnosticSnapshot build() {
            return new DiagnosticSnapshot(this);
        }
    }
}
