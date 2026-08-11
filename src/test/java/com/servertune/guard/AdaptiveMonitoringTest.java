package com.servertune.guard;

import com.servertune.fallback.FallbackState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 5: monitoring frequency scales with state, without becoming a feedback loop.
 */
class AdaptiveMonitoringTest {

    @Test
    void healthyServersUseTheConfiguredInterval() {
        AdaptiveMonitoring monitoring = AdaptiveMonitoring.defaults(100);

        assertEquals(100, monitoring.intervalFor(FallbackState.NORMAL));
    }

    @Test
    void degradedStatesSampleMoreOftenAndFallbackBacksOff() {
        AdaptiveMonitoring monitoring = AdaptiveMonitoring.defaults(100);

        assertEquals(75, monitoring.intervalFor(FallbackState.WARNING));
        assertEquals(50, monitoring.intervalFor(FallbackState.CRITICAL));

        // Fallback must do LESS work, not more - that is the anti-feedback-loop rule.
        assertTrue(monitoring.intervalFor(FallbackState.FALLBACK) > 100,
                "fallback must sample less often than normal, not more");
    }

    @Test
    void theFloorBoundsHowFastAnyConfigCanSample() {
        AdaptiveMonitoring monitoring = AdaptiveMonitoring.builder()
                .baseIntervalTicks(5)
                .criticalMultiplier(0.01)
                .build();

        assertEquals(AdaptiveMonitoring.FLOOR_TICKS,
                monitoring.intervalFor(FallbackState.CRITICAL),
                "no config may make the guard sample faster than the floor");
    }

    @Test
    void theCeilingBoundsHowSlowAnyConfigCanSample() {
        AdaptiveMonitoring monitoring = AdaptiveMonitoring.builder()
                .baseIntervalTicks(600)
                .fallbackMultiplier(50.0)
                .build();

        assertEquals(AdaptiveMonitoring.CEILING_TICKS,
                monitoring.intervalFor(FallbackState.FALLBACK),
                "the guard must never stop watching for recovery");
    }

    @Test
    void disablingAdaptiveMonitoringPinsTheBaseInterval() {
        AdaptiveMonitoring monitoring = AdaptiveMonitoring.builder()
                .enabled(false)
                .baseIntervalTicks(100)
                .build();

        for (FallbackState state : FallbackState.values()) {
            assertEquals(100, monitoring.intervalFor(state));
        }
    }

    @Test
    void reschedulingIsSkippedWhenTheIntervalIsUnchanged() {
        AdaptiveMonitoring monitoring = AdaptiveMonitoring.defaults(100);

        assertFalse(monitoring.requiresReschedule(FallbackState.NORMAL, FallbackState.RECOVERING),
                "identical intervals must not cause a needless task churn");
        assertTrue(monitoring.requiresReschedule(FallbackState.NORMAL, FallbackState.CRITICAL));
    }

    @Test
    void everyStateHasABoundedInterval() {
        AdaptiveMonitoring monitoring = AdaptiveMonitoring.defaults(100);

        for (FallbackState state : FallbackState.values()) {
            int interval = monitoring.intervalFor(state);
            assertTrue(interval >= AdaptiveMonitoring.FLOOR_TICKS
                            && interval <= AdaptiveMonitoring.CEILING_TICKS,
                    state + " produced an out-of-range interval: " + interval);
        }
    }
}
