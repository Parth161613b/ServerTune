package com.servertune.redstone;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-chunk redstone activity counters.
 *
 * <p>Rate limiting uses a tick-stamped window rather than wall-clock time, so it stays
 * aligned with the server tick loop and costs one comparison per event.
 */
public class RedstoneActivity {

    /** One second of ticks; the window used for per-second limits. */
    private static final int WINDOW_TICKS = 20;

    private final String worldName;
    private final int chunkX;
    private final int chunkZ;

    // Lifetime totals
    private final AtomicLong totalPistonMoves;
    private final AtomicLong totalDispenses;
    private final AtomicLong totalObserverUpdates;
    private final AtomicLong totalRedstoneChanges;

    // Sliding one-second counters
    private final AtomicInteger pistonThisSecond;
    private final AtomicInteger dispenseThisSecond;
    private volatile int pistonWindowStartTick;
    private volatile int dispenseWindowStartTick;

    // Counters for the reporting window, reset by the module each cycle
    private final AtomicLong pistonThisReportWindow;
    private final AtomicLong dispenseThisReportWindow;
    private final AtomicLong observerThisReportWindow;
    private final AtomicLong redstoneThisReportWindow;

    // Enforcement bookkeeping
    private final AtomicLong blockedActions;
    private volatile int cooldownUntilTick;
    private volatile int lastActivityTick;

    public RedstoneActivity(String worldName, int chunkX, int chunkZ) {
        this.worldName = worldName;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;

        this.totalPistonMoves = new AtomicLong(0);
        this.totalDispenses = new AtomicLong(0);
        this.totalObserverUpdates = new AtomicLong(0);
        this.totalRedstoneChanges = new AtomicLong(0);

        this.pistonThisSecond = new AtomicInteger(0);
        this.dispenseThisSecond = new AtomicInteger(0);
        this.pistonWindowStartTick = 0;
        this.dispenseWindowStartTick = 0;

        this.pistonThisReportWindow = new AtomicLong(0);
        this.dispenseThisReportWindow = new AtomicLong(0);
        this.observerThisReportWindow = new AtomicLong(0);
        this.redstoneThisReportWindow = new AtomicLong(0);

        this.blockedActions = new AtomicLong(0);
        this.cooldownUntilTick = -1;
        this.lastActivityTick = -1;
    }

    /**
     * Records a piston move and returns the count within the current one-second window.
     * Rolls the window when a second has elapsed.
     */
    public int recordPistonMove(int currentTick) {
        totalPistonMoves.incrementAndGet();
        pistonThisReportWindow.incrementAndGet();
        lastActivityTick = currentTick;

        if (currentTick - pistonWindowStartTick >= WINDOW_TICKS) {
            pistonWindowStartTick = currentTick;
            pistonThisSecond.set(1);
            return 1;
        }

        return pistonThisSecond.incrementAndGet();
    }

    public int recordDispense(int currentTick) {
        totalDispenses.incrementAndGet();
        dispenseThisReportWindow.incrementAndGet();
        lastActivityTick = currentTick;

        if (currentTick - dispenseWindowStartTick >= WINDOW_TICKS) {
            dispenseWindowStartTick = currentTick;
            dispenseThisSecond.set(1);
            return 1;
        }

        return dispenseThisSecond.incrementAndGet();
    }

    public void recordObserverUpdate(int currentTick) {
        totalObserverUpdates.incrementAndGet();
        observerThisReportWindow.incrementAndGet();
        lastActivityTick = currentTick;
    }

    public void recordRedstoneChange(int currentTick) {
        totalRedstoneChanges.incrementAndGet();
        redstoneThisReportWindow.incrementAndGet();
        lastActivityTick = currentTick;
    }

    public void recordBlocked() {
        blockedActions.incrementAndGet();
    }

    /** Puts this chunk's redstone on cooldown for the given number of ticks. */
    public void startCooldown(int currentTick, int cooldownTicks) {
        if (cooldownTicks > 0) {
            this.cooldownUntilTick = currentTick + cooldownTicks;
        }
    }

    public boolean isOnCooldown(int currentTick) {
        return cooldownUntilTick > 0 && currentTick < cooldownUntilTick;
    }

    /** Reads and clears the reporting-window counters. Returns the values before clearing. */
    public Window rollReportWindow() {
        return new Window(
                pistonThisReportWindow.getAndSet(0),
                dispenseThisReportWindow.getAndSet(0),
                observerThisReportWindow.getAndSet(0),
                redstoneThisReportWindow.getAndSet(0));
    }

    public record Window(long pistonMoves, long dispenses, long observerUpdates, long redstoneChanges) {
        public long total() {
            return pistonMoves + dispenses + observerUpdates + redstoneChanges;
        }
    }

    public boolean isIdle(int currentTick, int idleTicks) {
        return lastActivityTick < 0 || currentTick - lastActivityTick > idleTicks;
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

    public long getTotalPistonMoves() {
        return totalPistonMoves.get();
    }

    public long getTotalDispenses() {
        return totalDispenses.get();
    }

    public long getTotalObserverUpdates() {
        return totalObserverUpdates.get();
    }

    public long getTotalRedstoneChanges() {
        return totalRedstoneChanges.get();
    }

    public long getBlockedActions() {
        return blockedActions.get();
    }

    public int getPistonThisSecond() {
        return pistonThisSecond.get();
    }

    public int getDispenseThisSecond() {
        return dispenseThisSecond.get();
    }

    public int getLastActivityTick() {
        return lastActivityTick;
    }

    public long getLifetimeTotal() {
        return totalPistonMoves.get() + totalDispenses.get()
                + totalObserverUpdates.get() + totalRedstoneChanges.get();
    }

    @Override
    public String toString() {
        return String.format("RedstoneActivity{%s %d,%d pistons=%d dispenses=%d observers=%d changes=%d blocked=%d}",
                worldName, chunkX, chunkZ, totalPistonMoves.get(), totalDispenses.get(),
                totalObserverUpdates.get(), totalRedstoneChanges.get(), blockedActions.get());
    }
}
