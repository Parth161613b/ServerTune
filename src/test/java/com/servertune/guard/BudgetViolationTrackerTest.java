package com.servertune.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BudgetViolationTrackerTest {

    private BudgetViolationTracker tracker() {
        return BudgetViolationTracker.builder()
                .violationsBeforeSuspend(3)
                .defaultBudgetMs(5.0)
                .budget("health-monitor", 5.0)
                .build();
    }

    @Test
    void withinBudgetIsAlwaysOk() {
        BudgetViolationTracker tracker = tracker();

        for (int i = 0; i < 100; i++) {
            assertSame(BudgetViolationTracker.Decision.OK,
                    tracker.record("item-optimization", 4.9));
        }
        assertEquals(0, tracker.getConsecutiveViolations("item-optimization"));
    }

    @Test
    void suspensionNeedsTheConfiguredConsecutiveCount() {
        BudgetViolationTracker tracker = tracker();

        assertSame(BudgetViolationTracker.Decision.WARN,
                tracker.record("item-optimization", 20.0));
        assertSame(BudgetViolationTracker.Decision.WARN,
                tracker.record("item-optimization", 20.0));
        assertSame(BudgetViolationTracker.Decision.SUSPEND,
                tracker.record("item-optimization", 20.0));
    }

    @Test
    void oneGoodExecutionClearsTheStreak() {
        BudgetViolationTracker tracker = tracker();

        tracker.record("item-optimization", 20.0);
        tracker.record("item-optimization", 20.0);
        assertEquals(2, tracker.getConsecutiveViolations("item-optimization"));

        // A GC pause inside one timed block must not count towards suspension.
        assertSame(BudgetViolationTracker.Decision.OK,
                tracker.record("item-optimization", 1.0));
        assertEquals(0, tracker.getConsecutiveViolations("item-optimization"));

        assertSame(BudgetViolationTracker.Decision.WARN,
                tracker.record("item-optimization", 20.0));
    }

    @Test
    void protectedSubsystemsWarnButAreNeverSuspended() {
        BudgetViolationTracker tracker = tracker();

        tracker.record("health-monitor", 50.0);
        tracker.record("health-monitor", 50.0);

        // Suspending the health monitor would blind the guard and make recovery undetectable.
        for (int i = 0; i < 10; i++) {
            assertSame(BudgetViolationTracker.Decision.WARN_PROTECTED,
                    tracker.record("health-monitor", 50.0));
        }
    }

    @Test
    void suspendOnExceededFalseDowngradesToWarning() {
        BudgetViolationTracker tracker = BudgetViolationTracker.builder()
                .violationsBeforeSuspend(2)
                .suspendOnExceeded(false)
                .defaultBudgetMs(5.0)
                .build();

        tracker.record("item-optimization", 20.0);
        assertSame(BudgetViolationTracker.Decision.WARN,
                tracker.record("item-optimization", 20.0));
        assertSame(BudgetViolationTracker.Decision.WARN,
                tracker.record("item-optimization", 20.0));
    }

    @Test
    void disabledTrackerNeverActs() {
        BudgetViolationTracker tracker = BudgetViolationTracker.builder()
                .enabled(false)
                .defaultBudgetMs(5.0)
                .build();

        for (int i = 0; i < 10; i++) {
            assertSame(BudgetViolationTracker.Decision.OK,
                    tracker.record("item-optimization", 500.0));
        }
    }

    @Test
    void unnamedSubsystemsUseTheSharedModuleBudget() {
        BudgetViolationTracker tracker = tracker();

        assertEquals(5.0, tracker.budgetFor("redstone-optimization"));
        assertEquals(5.0, tracker.budgetFor("health-monitor"));
    }

    @Test
    void violationCountsAreTrackedPerSubsystem() {
        BudgetViolationTracker tracker = tracker();

        tracker.record("item-optimization", 20.0);
        tracker.record("item-optimization", 20.0);
        tracker.record("hopper-optimization", 20.0);

        assertEquals(2, tracker.getConsecutiveViolations("item-optimization"));
        assertEquals(1, tracker.getConsecutiveViolations("hopper-optimization"));
        assertEquals(0, tracker.getConsecutiveViolations("redstone-optimization"));
        assertEquals(2, tracker.getTotalViolations("item-optimization"));
    }
}
