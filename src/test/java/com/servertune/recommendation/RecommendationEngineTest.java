package com.servertune.recommendation;

import com.servertune.diagnostics.ChunkHotspot;
import com.servertune.diagnostics.DiagnosticSnapshot;
import com.servertune.diagnostics.DiagnosticThresholds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 3: recommendations are generated from measurements, and must never modify
 * configuration. The engine has no config handle at all, so these tests cover the other half:
 * that it only suggests what is currently off, and always names the key to edit.
 */
class RecommendationEngineTest {

    private static final long NOW = 2_000_000L;

    private final DiagnosticThresholds thresholds = DiagnosticThresholds.defaults();
    private final RecommendationEngine engine = new RecommendationEngine(thresholds);

    private static DiagnosticSnapshot.Builder quiet() {
        return DiagnosticSnapshot.builder()
                .sampledAt(NOW)
                .tps(20.0)
                .mspt(10.0)
                .loadedChunks(300)
                .totalEntities(150);
    }

    @Test
    void quietServerGetsNoSuggestions() {
        RecommendationReport report = engine.recommend(quiet().build(), NOW);

        assertFalse(report.hasRecommendations(),
                "with nothing above a threshold there is nothing to suggest");
        assertFalse(report.isEmpty());
    }

    @Test
    void nullSnapshotIsUnavailable() {
        RecommendationReport report = engine.recommend(null, NOW);

        assertTrue(report.isEmpty());
        assertFalse(report.hasRecommendations());
    }

    @Test
    void manyDroppedItemsSuggestsTheItemModule() {
        RecommendationReport report = engine.recommend(quiet().itemCount(1500).build(), NOW);

        Recommendation r = only(report, "item optimization");
        assertEquals("optimization.modules.item.enabled", r.configPath());
        assertTrue(r.evidence().stream().anyMatch(e -> e.contains("1500 dropped items")),
                "the suggestion must show the measurement it came from");
    }

    @Test
    void criticalItemCountRaisesThePriority() {
        Recommendation medium = only(engine.recommend(quiet().itemCount(600).build(), NOW),
                "item optimization");
        Recommendation high = only(engine.recommend(quiet().itemCount(5000).build(), NOW),
                "item optimization");

        assertEquals(Recommendation.Priority.MEDIUM, medium.priority());
        assertEquals(Recommendation.Priority.HIGH, high.priority());
    }

    /** Suggesting a module that is already running is noise, so it must not appear. */
    @Test
    void enabledModuleIsNotSuggestedAgain() {
        RecommendationReport report = engine.recommend(quiet()
                .itemCount(1500)
                .enabledModules(Set.of("item-optimization"))
                .itemMergingEnabled(true)
                .build(), NOW);

        assertTrue(report.recommendations().stream()
                        .noneMatch(r -> r.title().contains("item optimization")),
                "the item module is already on");
    }

    /** With the module on but the sub-setting off, the narrower suggestion is the useful one. */
    @Test
    void enabledModuleWithMergingOffSuggestsMergingOnly() {
        RecommendationReport report = engine.recommend(quiet()
                .itemCount(1500)
                .enabledModules(Set.of("item-optimization"))
                .itemMergingEnabled(false)
                .build(), NOW);

        Recommendation r = only(report, "item merging");
        assertEquals("optimization.modules.item.merging.enabled", r.configPath());
    }

    @Test
    void manyInactiveChunksSuggestsUnloading() {
        Recommendation r = only(engine.recommend(quiet()
                .inactiveChunks(800)
                .trackedChunks(2000)
                .build(), NOW), "inactive chunk unloading");

        assertEquals("optimization.modules.chunk.enabled", r.configPath());
        assertTrue(r.hasGameplayNote(), "unloading pauses farms; that has to be stated");
    }

    @Test
    void highChunkLoadRateSuggestsMonitoring() {
        RecommendationReport report = engine.recommend(quiet()
                .chunkLoadRate(200)
                .chunkUnloadRate(180)
                .forceLoadedChunks(12)
                .build(), NOW);

        Recommendation r = only(report, "chunk loading");
        assertEquals(Recommendation.Priority.HIGH, r.priority());
        assertTrue(r.evidence().stream().anyMatch(e -> e.contains("12 chunks are force-loaded")));
    }

    @Test
    void denseHopperChunkSuggestsHopperOptimization() {
        ChunkHotspot dense = ChunkHotspot.builder("world", 124, -37)
                .hopperCount(31)
                .score(1.3)
                .build();

        Recommendation r = only(engine.recommend(quiet().hotspots(List.of(dense)).build(), NOW),
                "hopper optimization");

        assertEquals("optimization.modules.hopper.enabled", r.configPath());
        assertTrue(r.evidence().stream()
                .anyMatch(e -> e.contains("31 hoppers in chunk 124,-37")));
    }

    @Test
    void entityLimitsCarryAnExplicitGameplayWarning() {
        Recommendation r = only(engine.recommend(quiet()
                .totalEntities(12_000)
                .mobCount(9000)
                .build(), NOW), "entity limits");

        assertTrue(r.gameplayNote().contains("HIGH gameplay impact"),
                "capping mob density can break farms; the reader has to be told before enabling");
    }

    /** An unmeasured hopper count must not be read as a hopper-free server or as a busy one. */
    @Test
    void unmeasuredHopperCountAloneSuggestsNothing() {
        DiagnosticSnapshot s = quiet().build();

        assertFalse(s.hasHopperCount());
        assertTrue(engine.recommend(s, NOW).recommendations().stream()
                .noneMatch(r -> r.title().contains("hopper")));
    }

    @Test
    void suggestionsAreOrderedByDescendingPriority() {
        RecommendationReport report = engine.recommend(quiet()
                .itemCount(5000)          // HIGH
                .xpOrbCount(400)          // MEDIUM
                .inactiveChunks(900)      // MEDIUM
                .build(), NOW);

        List<Recommendation> out = report.recommendations();
        assertTrue(out.size() >= 2);
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i - 1).priority().atLeast(out.get(i).priority()),
                    "highest priority first");
        }
        assertEquals(Recommendation.Priority.HIGH, out.get(0).priority());
    }

    @Test
    void everySuggestionNamesSomethingActionable() {
        RecommendationReport report = engine.recommend(quiet()
                .itemCount(5000)
                .xpOrbCount(400)
                .inactiveChunks(900)
                .chunkLoadRate(200)
                .totalEntities(12_000)
                .build(), NOW);

        assertTrue(report.hasRecommendations());
        for (Recommendation r : report.recommendations()) {
            assertFalse(r.evidence().isEmpty(),
                    r.title() + " must show the measurement behind it");
            assertTrue(r.hasConfigPath() || r.hasGameplayNote(),
                    r.title() + " must say what the reader would change");
        }
    }

    @Test
    void ageAndStalenessAreReportedFromTheCallerClock() {
        DiagnosticSnapshot s = quiet().itemCount(1500).build();

        assertEquals(3L, engine.recommend(s, NOW + 3_000L).ageSeconds());
        assertFalse(engine.recommend(s, NOW + 3_000L).stale());
        assertTrue(engine.recommend(s, NOW + 60_000L).stale());
    }

    private static Recommendation only(RecommendationReport report, String titleFragment) {
        List<Recommendation> matches = report.recommendations().stream()
                .filter(r -> r.title().contains(titleFragment))
                .toList();
        assertEquals(1, matches.size(),
                "expected exactly one recommendation mentioning " + titleFragment);
        return matches.get(0);
    }
}
