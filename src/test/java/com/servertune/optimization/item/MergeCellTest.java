package com.servertune.optimization.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invariant the cell-bucketed merge rests on.
 *
 * <p>The sweep only compares an item against its own cell and the thirteen forward-neighbour
 * cells. That is correct <b>only if</b> any two points within the merge radius always land in
 * cells no more than one step apart on every axis. If that ever failed, the module would
 * silently stop merging some pairs it used to merge - a behaviour change with no error and no
 * log line, which is exactly the kind of regression a unit test should be holding down.
 *
 * <p>These methods are static and take no Bukkit type, so they are reachable from a test even
 * though paper-api is {@code compileOnly}.
 */
class MergeCellTest {

    private static final double RADIUS = 3.0;

    @Test
    void pointsWithinTheRadiusNeverLandMoreThanOneCellApart() {
        // Walk a grid finer than the cell size across a span of several cells, including the
        // negative side of every axis, and check every pair the sweep is required to find.
        double step = 0.75;
        double span = 6.0;

        int checked = 0;
        for (double ax = -span; ax <= span; ax += step) {
            for (double ay = -span; ay <= span; ay += step) {
                for (double az = -span; az <= span; az += step) {
                    for (double bx = ax; bx <= span; bx += step) {
                        for (double by = -span; by <= span; by += step) {
                            for (double bz = -span; bz <= span; bz += step) {
                                double dx = bx - ax;
                                double dy = by - ay;
                                double dz = bz - az;
                                if (dx * dx + dy * dy + dz * dz > RADIUS * RADIUS) {
                                    continue;
                                }

                                assertTrue(withinOneCell(ax, bx), "x cells too far apart");
                                assertTrue(withinOneCell(ay, by), "y cells too far apart");
                                assertTrue(withinOneCell(az, bz), "z cells too far apart");
                                checked++;
                            }
                        }
                    }
                }
            }
        }

        assertTrue(checked > 1000, "the sweep should have examined a meaningful number of pairs");
    }

    private static boolean withinOneCell(double a, double b) {
        int ca = ItemOptimizationModule.cellIndex(a, RADIUS);
        int cb = ItemOptimizationModule.cellIndex(b, RADIUS);
        return Math.abs(ca - cb) <= 1;
    }

    /**
     * Integer division truncates toward zero, so -0.5 and 0.5 would share a cell while -3.5 and
     * -0.5 would not. {@code Math.floor} is what makes the boundary behave the same either side
     * of zero, and merges near x=0 depend on it.
     */
    @Test
    void cellBoundariesBehaveTheSameEitherSideOfZero() {
        assertEquals(-1, ItemOptimizationModule.cellIndex(-0.5, RADIUS));
        assertEquals(0, ItemOptimizationModule.cellIndex(0.5, RADIUS));
        assertEquals(-1, ItemOptimizationModule.cellIndex(-3.0, RADIUS));
        assertEquals(-2, ItemOptimizationModule.cellIndex(-3.5, RADIUS));

        // Every cell must be exactly one radius wide, negatives included.
        for (int cell = -4; cell <= 4; cell++) {
            double low = cell * RADIUS;
            assertEquals(cell, ItemOptimizationModule.cellIndex(low, RADIUS));
            assertEquals(cell, ItemOptimizationModule.cellIndex(low + RADIUS - 0.001, RADIUS));
        }
    }

    /** Distinct nearby cells must pack to distinct keys, or the binary search finds the wrong run. */
    @Test
    void neighbouringCellsPackToDistinctKeys() {
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    long key = ItemOptimizationModule.cellKey(x, y, z);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) {
                                    continue;
                                }
                                assertNotEquals(key,
                                        ItemOptimizationModule.cellKey(x + dx, y + dy, z + dz),
                                        "cells one step apart must not share a key");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The cell math against an explicit statement of what it is supposed to be. The XP module
     * runs the same sweep with its own copy of these two methods, and
     * {@code XPMergeCellTest} holds it to this same reference - so if either module's
     * arithmetic drifted, one of them would be bucketing on different boundaries than its own
     * neighbour offsets assume, and one of the two tests would fail.
     */
    @Test
    void theCellMathMatchesItsSpecification() {
        for (double coordinate = -20.0; coordinate <= 20.0; coordinate += 0.37) {
            assertEquals((int) Math.floor(coordinate / RADIUS),
                    ItemOptimizationModule.cellIndex(coordinate, RADIUS));
        }

        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    long expected = ((long) x & 0x1FFFFFL) << 42
                            | ((long) y & 0x1FFFFFL) << 21
                            | ((long) z & 0x1FFFFFL);
                    assertEquals(expected, ItemOptimizationModule.cellKey(x, y, z));
                }
            }
        }
    }
}
