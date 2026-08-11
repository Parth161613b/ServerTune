package com.servertune.redstone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedstoneModeTest {

    @Test
    void vanillaDisablesMonitoringEntirely() {
        assertFalse(RedstoneMode.VANILLA.isMonitoringEnabled());
        assertFalse(RedstoneMode.VANILLA.altersGameplay());
    }

    @Test
    void conservativeMonitorsWithoutAlteringGameplay() {
        assertTrue(RedstoneMode.CONSERVATIVE.isMonitoringEnabled());
        assertFalse(RedstoneMode.CONSERVATIVE.altersGameplay(),
                "conservative must never cancel anything");
        assertEquals(-1, RedstoneMode.CONSERVATIVE.getPistonPerChunkPerSecond(),
                "-1 means no limit");
    }

    @Test
    void limitsTightenFromBalancedToAggressive() {
        assertTrue(RedstoneMode.BALANCED.getPistonPerChunkPerSecond()
                        > RedstoneMode.AGGRESSIVE.getPistonPerChunkPerSecond(),
                "aggressive must be stricter than balanced");

        assertTrue(RedstoneMode.BALANCED.getDispenserPerChunkPerSecond()
                > RedstoneMode.AGGRESSIVE.getDispenserPerChunkPerSecond());

        assertTrue(RedstoneMode.BALANCED.altersGameplay());
        assertTrue(RedstoneMode.AGGRESSIVE.altersGameplay());
    }

    @Test
    void onlyAggressiveAppliesACooldownByDefault() {
        assertEquals(0, RedstoneMode.BALANCED.getPistonCooldownTicks());
        assertTrue(RedstoneMode.AGGRESSIVE.getPistonCooldownTicks() > 0);
    }

    @Test
    void parsingIsCaseInsensitiveAndFallsBackSafely() {
        assertEquals(RedstoneMode.AGGRESSIVE, RedstoneMode.fromString("aggressive", RedstoneMode.VANILLA));
        assertEquals(RedstoneMode.BALANCED, RedstoneMode.fromString("  BaLaNcEd  ", RedstoneMode.VANILLA));

        // A typo must not silently escalate to a gameplay-altering mode
        assertEquals(RedstoneMode.CONSERVATIVE,
                RedstoneMode.fromString("agressive", RedstoneMode.CONSERVATIVE));
        assertEquals(RedstoneMode.CONSERVATIVE,
                RedstoneMode.fromString(null, RedstoneMode.CONSERVATIVE));
        assertEquals(RedstoneMode.CONSERVATIVE,
                RedstoneMode.fromString("", RedstoneMode.CONSERVATIVE));
    }

    @Test
    void everyModeIsParseableFromItsOwnName() {
        for (RedstoneMode mode : RedstoneMode.values()) {
            assertEquals(mode, RedstoneMode.fromString(mode.name().toLowerCase(), RedstoneMode.VANILLA));
        }
    }
}
