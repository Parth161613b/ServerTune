package com.servertune.optimization.xp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The XP module's copy of the cell-bucketed merge invariant.
 *
 * <p>The two modules run the same sweep over their own entity type, each with its own copy of
 * {@code cellIndex} and {@code cellKey}. The methods are package-private, so neither test can
 * reach the other module's - instead both classes check their own module against the same
 * explicit reference formula in {@code theCellMathMatchesItsSpecification}. If either module's
 * arithmetic drifted, that module would be bucketing on different boundaries than its own
 * thirteen neighbour offsets assume, and its own test would fail.
 *
 * <p>See {@code com.servertune.optimization.item.MergeCellTest} for why the one-cell invariant
 * is the thing worth holding down: breaking it makes the module silently stop merging pairs it
 * used to merge, with no error and no log line.
 */
class XPMergeCellTest {

    private static final double RADIUS = 3.0;

    @Test
    void orbsWithinTheRadiusNeverLandMoreThanOneCellApart() {
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
        int ca = XPOptimizationModule.cellIndex(a, RADIUS);
        int cb = XPOptimizationModule.cellIndex(b, RADIUS);
        return Math.abs(ca - cb) <= 1;
    }

    /** Merges near x=0 depend on the boundary behaving the same either side of zero. */
    @Test
    void cellBoundariesBehaveTheSameEitherSideOfZero() {
        assertEquals(-1, XPOptimizationModule.cellIndex(-0.5, RADIUS));
        assertEquals(0, XPOptimizationModule.cellIndex(0.5, RADIUS));
        assertEquals(-1, XPOptimizationModule.cellIndex(-3.0, RADIUS));
        assertEquals(-2, XPOptimizationModule.cellIndex(-3.5, RADIUS));

        for (int cell = -4; cell <= 4; cell++) {
            double low = cell * RADIUS;
            assertEquals(cell, XPOptimizationModule.cellIndex(low, RADIUS));
            assertEquals(cell, XPOptimizationModule.cellIndex(low + RADIUS - 0.001, RADIUS));
        }
    }

    /** Distinct nearby cells must pack to distinct keys, or the binary search finds the wrong run. */
    @Test
    void neighbouringCellsPackToDistinctKeys() {
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    long key = XPOptimizationModule.cellKey(x, y, z);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) {
                                    continue;
                                }
                                assertNotEquals(key,
                                        XPOptimizationModule.cellKey(x + dx, y + dy, z + dz),
                                        "cells one step apart must not share a key");
                            }
                        }
                    }
                }
            }
        }
    }

    /** The same reference formula the item module's test checks itself against. */
    @Test
    void theCellMathMatchesItsSpecification() {
        for (double coordinate = -20.0; coordinate <= 20.0; coordinate += 0.37) {
            assertEquals((int) Math.floor(coordinate / RADIUS),
                    XPOptimizationModule.cellIndex(coordinate, RADIUS));
        }

        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    long expected = ((long) x & 0x1FFFFFL) << 42
                            | ((long) y & 0x1FFFFFL) << 21
                            | ((long) z & 0x1FFFFFL);
                    assertEquals(expected, XPOptimizationModule.cellKey(x, y, z));
                }
            }
        }
    }
}
