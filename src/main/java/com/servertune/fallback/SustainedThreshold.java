package com.servertune.fallback;

public class SustainedThreshold {

    private final double threshold;
    private final long sustainedMillis;
    private long firstViolationTime = 0;
    private boolean active = false;

    public SustainedThreshold(double threshold, int sustainedSeconds) {
        this.threshold = threshold;
        this.sustainedMillis = sustainedSeconds * 1000L;
    }

    /**
     * Check if value violates threshold (below for TPS, above for MSPT).
     * Returns true only after sustained for configured duration.
     */
    public boolean checkBelow(double currentValue) {
        long now = System.currentTimeMillis();

        if (currentValue < threshold) {
            // Violation
            if (firstViolationTime == 0) {
                firstViolationTime = now;
            }

            // Check if sustained long enough
            if (now - firstViolationTime >= sustainedMillis) {
                active = true;
                return true;
            }
        } else {
            // No violation - reset
            reset();
        }

        return false;
    }

    /**
     * Check if value violates threshold (above for MSPT).
     */
    public boolean checkAbove(double currentValue) {
        long now = System.currentTimeMillis();

        if (currentValue > threshold) {
            // Violation
            if (firstViolationTime == 0) {
                firstViolationTime = now;
            }

            // Check if sustained long enough
            if (now - firstViolationTime >= sustainedMillis) {
                active = true;
                return true;
            }
        } else {
            // No violation - reset
            reset();
        }

        return false;
    }

    public void reset() {
        firstViolationTime = 0;
        active = false;
    }

    public boolean isActive() {
        return active;
    }

    public double getThreshold() {
        return threshold;
    }

    public long getSustainedMillis() {
        return sustainedMillis;
    }

    public long getTimeInViolation() {
        if (firstViolationTime == 0) {
            return 0;
        }
        return System.currentTimeMillis() - firstViolationTime;
    }
}
