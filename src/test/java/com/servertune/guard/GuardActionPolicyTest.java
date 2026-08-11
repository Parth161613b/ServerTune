package com.servertune.guard;

import com.servertune.fallback.FallbackState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 7: alerts and automatic optimization must be independently configurable.
 */
class GuardActionPolicyTest {

    @Test
    void defaultsAlertWithoutChangingAnything() {
        GuardActionPolicy policy = GuardActionPolicy.defaults();

        assertTrue(policy.isAlertsEnabled());
        assertFalse(policy.isAutomaticOptimizationEnabled(),
                "automatic optimization must be off by default");

        assertTrue(policy.shouldNotify(FallbackState.CRITICAL));
        assertTrue(policy.modulesToEnable(FallbackState.CRITICAL).isEmpty());
        assertTrue(policy.modulesToSuspend(FallbackState.CRITICAL).isEmpty());
    }

    @Test
    void alertsTrueWithOptimizationFalseIsTheDocumentedCombination() {
        GuardActionPolicy policy = GuardActionPolicy.builder()
                .alertsEnabled(true)
                .automaticOptimizationEnabled(false)
                .stateActions(FallbackState.WARNING, new GuardActionPolicy.StateActions(
                        true, List.of("item-optimization"), List.of()))
                .build();

        assertTrue(policy.shouldNotify(FallbackState.WARNING));
        assertTrue(policy.modulesToEnable(FallbackState.WARNING).isEmpty(),
                "with automatic-optimization off, a configured module list must be ignored");
    }

    @Test
    void optimizationTrueHonoursTheConfiguredLists() {
        GuardActionPolicy policy = GuardActionPolicy.builder()
                .automaticOptimizationEnabled(true)
                .stateActions(FallbackState.WARNING, new GuardActionPolicy.StateActions(
                        true, List.of("item-optimization"), List.of("chunk-optimization")))
                .build();

        assertEquals(List.of("item-optimization"), policy.modulesToEnable(FallbackState.WARNING));
        assertEquals(List.of("chunk-optimization"), policy.modulesToSuspend(FallbackState.WARNING));
    }

    @Test
    void alertsFalseSilencesNotificationsButNotActions() {
        GuardActionPolicy policy = GuardActionPolicy.builder()
                .alertsEnabled(false)
                .automaticOptimizationEnabled(true)
                .stateActions(FallbackState.CRITICAL, new GuardActionPolicy.StateActions(
                        true, List.of("item-optimization"), List.of()))
                .build();

        assertFalse(policy.shouldNotify(FallbackState.CRITICAL));
        assertEquals(List.of("item-optimization"), policy.modulesToEnable(FallbackState.CRITICAL));
    }

    @Test
    void notifyStaffIsPerState() {
        GuardActionPolicy policy = GuardActionPolicy.builder()
                .stateActions(FallbackState.WARNING, GuardActionPolicy.StateActions.none())
                .stateActions(FallbackState.CRITICAL, GuardActionPolicy.StateActions.notifyOnly())
                .build();

        assertFalse(policy.shouldNotify(FallbackState.WARNING));
        assertTrue(policy.shouldNotify(FallbackState.CRITICAL));
    }

    @Test
    void fallbackSelfSuspensionIsNotGatedOnAutomaticOptimization() {
        GuardActionPolicy policy = GuardActionPolicy.builder()
                .automaticOptimizationEnabled(false)
                .build();

        // Standing down the optimizer's own work is not an optimization of the server.
        assertTrue(policy.shouldSuspendOptimizerInFallback());
        assertTrue(policy.shouldCancelExpensiveTasksInFallback());
        assertTrue(policy.shouldStopDeepAnalysisInFallback());
    }

    @Test
    void unconfiguredStatesFallBackToNoActions() {
        GuardActionPolicy policy = GuardActionPolicy.builder()
                .automaticOptimizationEnabled(true)
                .build();

        assertFalse(policy.shouldNotify(FallbackState.NORMAL));
        assertTrue(policy.modulesToEnable(FallbackState.NORMAL).isEmpty());
    }
}
