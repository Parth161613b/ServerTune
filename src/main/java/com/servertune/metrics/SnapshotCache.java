package com.servertune.metrics;

import com.servertune.ServerTunePlugin;

import java.util.concurrent.atomic.AtomicReference;

public class SnapshotCache {

    private final ServerTunePlugin plugin;
    private final AtomicReference<HealthSnapshot> cachedSnapshot;
    private volatile long lastUpdateTime;

    public SnapshotCache(ServerTunePlugin plugin) {
        this.plugin = plugin;
        this.cachedSnapshot = new AtomicReference<>(null);
        this.lastUpdateTime = 0;
    }

    public void update(HealthSnapshot snapshot) {
        cachedSnapshot.set(snapshot);
        lastUpdateTime = System.currentTimeMillis();
    }

    public HealthSnapshot getLatest() {
        return cachedSnapshot.get();
    }

    public boolean hasSnapshot() {
        return cachedSnapshot.get() != null;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public long getTimeSinceUpdate() {
        if (lastUpdateTime == 0) {
            return Long.MAX_VALUE;
        }
        return System.currentTimeMillis() - lastUpdateTime;
    }

    public void clear() {
        cachedSnapshot.set(null);
        lastUpdateTime = 0;
    }
}
