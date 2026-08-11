package com.servertune.metrics;

import org.bukkit.entity.EntityType;

import java.util.Collections;
import java.util.Map;

public class HealthSnapshot {

    private final long timestamp;
    private final double tps;
    private final double mspt;
    private final double cpuUsage;
    private final long memoryUsed;
    private final long memoryAllocated;
    private final long memoryMax;
    private final int onlinePlayers;
    private final int maxPlayers;
    private final int loadedChunks;
    private final int totalEntities;
    private final Map<EntityType, Integer> entityCounts;
    private final int loadedWorlds;
    private final int loadedPlugins;
    private final int loadedDatapacks;
    private final String serverStatus;
    private final String activeProfile;

    private HealthSnapshot(Builder builder) {
        this.timestamp = builder.timestamp;
        this.tps = builder.tps;
        this.mspt = builder.mspt;
        this.cpuUsage = builder.cpuUsage;
        this.memoryUsed = builder.memoryUsed;
        this.memoryAllocated = builder.memoryAllocated;
        this.memoryMax = builder.memoryMax;
        this.onlinePlayers = builder.onlinePlayers;
        this.maxPlayers = builder.maxPlayers;
        this.loadedChunks = builder.loadedChunks;
        this.totalEntities = builder.totalEntities;
        this.entityCounts = Collections.unmodifiableMap(builder.entityCounts);
        this.loadedWorlds = builder.loadedWorlds;
        this.loadedPlugins = builder.loadedPlugins;
        this.loadedDatapacks = builder.loadedDatapacks;
        this.serverStatus = builder.serverStatus;
        this.activeProfile = builder.activeProfile;
    }

    // Getters
    public long getTimestamp() {
        return timestamp;
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

    public int getOnlinePlayers() {
        return onlinePlayers;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public int getLoadedChunks() {
        return loadedChunks;
    }

    public int getTotalEntities() {
        return totalEntities;
    }

    public Map<EntityType, Integer> getEntityCounts() {
        return entityCounts;
    }

    public int getLoadedWorlds() {
        return loadedWorlds;
    }

    public int getLoadedPlugins() {
        return loadedPlugins;
    }

    public int getLoadedDatapacks() {
        return loadedDatapacks;
    }

    public String getServerStatus() {
        return serverStatus;
    }

    public String getActiveProfile() {
        return activeProfile;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long timestamp;
        private double tps;
        private double mspt;
        private double cpuUsage;
        private long memoryUsed;
        private long memoryAllocated;
        private long memoryMax;
        private int onlinePlayers;
        private int maxPlayers;
        private int loadedChunks;
        private int totalEntities;
        private Map<EntityType, Integer> entityCounts = Collections.emptyMap();
        private int loadedWorlds;
        private int loadedPlugins;
        private int loadedDatapacks;
        private String serverStatus = "UNKNOWN";
        private String activeProfile = "balanced";

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder tps(double tps) {
            this.tps = tps;
            return this;
        }

        public Builder mspt(double mspt) {
            this.mspt = mspt;
            return this;
        }

        public Builder cpuUsage(double cpuUsage) {
            this.cpuUsage = cpuUsage;
            return this;
        }

        public Builder memoryUsed(long memoryUsed) {
            this.memoryUsed = memoryUsed;
            return this;
        }

        public Builder memoryAllocated(long memoryAllocated) {
            this.memoryAllocated = memoryAllocated;
            return this;
        }

        public Builder memoryMax(long memoryMax) {
            this.memoryMax = memoryMax;
            return this;
        }

        public Builder onlinePlayers(int onlinePlayers) {
            this.onlinePlayers = onlinePlayers;
            return this;
        }

        public Builder maxPlayers(int maxPlayers) {
            this.maxPlayers = maxPlayers;
            return this;
        }

        public Builder loadedChunks(int loadedChunks) {
            this.loadedChunks = loadedChunks;
            return this;
        }

        public Builder totalEntities(int totalEntities) {
            this.totalEntities = totalEntities;
            return this;
        }

        public Builder entityCounts(Map<EntityType, Integer> entityCounts) {
            this.entityCounts = entityCounts;
            return this;
        }

        public Builder loadedWorlds(int loadedWorlds) {
            this.loadedWorlds = loadedWorlds;
            return this;
        }

        public Builder loadedPlugins(int loadedPlugins) {
            this.loadedPlugins = loadedPlugins;
            return this;
        }

        public Builder loadedDatapacks(int loadedDatapacks) {
            this.loadedDatapacks = loadedDatapacks;
            return this;
        }

        public Builder serverStatus(String serverStatus) {
            this.serverStatus = serverStatus;
            return this;
        }

        public Builder activeProfile(String activeProfile) {
            this.activeProfile = activeProfile;
            return this;
        }

        public HealthSnapshot build() {
            return new HealthSnapshot(this);
        }
    }
}
