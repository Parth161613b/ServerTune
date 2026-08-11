package com.servertune.metrics.collector;

import com.servertune.ServerTunePlugin;

public class MemoryCollector {

    private final ServerTunePlugin plugin;

    public MemoryCollector(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    public MemoryStats getMemoryStats() {
        try {
            Runtime runtime = Runtime.getRuntime();

            long maxMemory = runtime.maxMemory(); // Maximum memory JVM can use
            long allocatedMemory = runtime.totalMemory(); // Currently allocated memory
            long freeMemory = runtime.freeMemory(); // Free memory within allocated
            long usedMemory = allocatedMemory - freeMemory; // Actually used memory

            return new MemoryStats(usedMemory, allocatedMemory, maxMemory);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get memory stats: " + e.getMessage());
            return new MemoryStats(0, 0, 0);
        }
    }

    public static class MemoryStats {
        public final long used;
        public final long allocated;
        public final long max;

        public MemoryStats(long used, long allocated, long max) {
            this.used = used;
            this.allocated = allocated;
            this.max = max;
        }
    }
}
