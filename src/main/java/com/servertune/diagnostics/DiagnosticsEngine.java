package com.servertune.diagnostics;

import com.servertune.ServerTunePlugin;
import com.servertune.recommendation.RecommendationEngine;
import com.servertune.recommendation.RecommendationReport;
import com.servertune.selfmonitor.ExecutionTimer;
import org.bukkit.Bukkit;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Owns the diagnostics snapshot and hands it to the pure engines.
 *
 * <p>The split matters:
 * <ul>
 *   <li>{@link DiagnosticSampler} runs on the main thread on a timer, and is the only part
 *       that touches Bukkit</li>
 *   <li>{@link DiagnosticAnalyzer} and {@link RecommendationEngine} are pure, so
 *       {@link #analyzeAsync} and {@link #recommendAsync} can run them off-thread against an
 *       immutable snapshot with no unsafe API access at all</li>
 * </ul>
 *
 * <p><b>Commands never trigger a sample.</b> {@link #getLatestSnapshot()} returns whatever
 * the periodic task last stored, which may be null before the first cycle and may be stale.
 * Callers are expected to report the age rather than silently refresh - a diagnose command
 * that scanned the world on demand would be exactly the tick spike it is meant to detect.
 */
public class DiagnosticsEngine {

    /** Scheduler task name, so the guard's fallback handling can find it. */
    public static final String SAMPLE_TASK = "diagnostics-sample";

    private final ServerTunePlugin plugin;
    private final DiagnosticThresholds thresholds;
    private final DiagnosticAnalyzer analyzer;
    private final RecommendationEngine recommendationEngine;
    private final DiagnosticSampler sampler;
    private final AtomicReference<DiagnosticSnapshot> latest = new AtomicReference<>();
    private final boolean enabled;
    private final boolean async;
    private final boolean recommendationsEnabled;
    private final int sampleIntervalTicks;

    public DiagnosticsEngine(ServerTunePlugin plugin) {
        this.plugin = plugin;
        this.thresholds = DiagnosticsConfigLoader.loadThresholds(plugin.getConfig());
        this.analyzer = new DiagnosticAnalyzer(thresholds);
        this.recommendationEngine = new RecommendationEngine(thresholds);
        this.sampler = new DiagnosticSampler(plugin, new HotspotRanker(thresholds),
                DiagnosticsConfigLoader.getChunkScanBudget(plugin.getConfig()));
        this.enabled = DiagnosticsConfigLoader.isEnabled(plugin.getConfig());
        this.async = DiagnosticsConfigLoader.isAsync(plugin.getConfig());
        this.recommendationsEnabled =
                DiagnosticsConfigLoader.areRecommendationsEnabled(plugin.getConfig());
        this.sampleIntervalTicks =
                DiagnosticsConfigLoader.getSampleIntervalTicks(plugin.getConfig());
    }

    /**
     * Schedules the periodic sample. Registered with the scheduler under {@link #SAMPLE_TASK}
     * so the performance guard can cancel it in FALLBACK along with the other expensive work.
     */
    public void start() {
        if (!enabled) {
            plugin.getLogger().info("[Diagnostics] Disabled by config; no sampling scheduled");
            return;
        }

        plugin.getScheduler().scheduleTask(SAMPLE_TASK, this::sampleNow,
                sampleIntervalTicks, sampleIntervalTicks);
    }

    /**
     * Takes one sample. Main thread only - it reads chunks and entities.
     *
     * <p>Skipped while the guard has deep analysis stopped: on a server already in fallback,
     * a per-chunk pass is precisely the work that should not be running.
     */
    public void sampleNow() {
        if (!enabled) {
            return;
        }

        if (plugin.getPerformanceGuard() != null
                && !plugin.getPerformanceGuard().isDeepAnalysisAllowed()) {
            return;
        }

        ExecutionTimer timer = plugin.getSelfMonitor().startTimer("deep-analysis");
        try {
            latest.set(sampler.sample(plugin.getSnapshotCache().getLatest()));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Diagnostics] Sampling failed", e);
        } finally {
            timer.stop();
        }
    }

    /** The last periodic sample, or null before the first cycle. Never triggers a sample. */
    public DiagnosticSnapshot getLatestSnapshot() {
        return latest.get();
    }

    /** Diagnoses the cached snapshot on the calling thread. */
    public DiagnosticReport diagnose() {
        return analyzer.analyze(latest.get(), System.currentTimeMillis());
    }

    /**
     * Diagnoses off-thread, then delivers the report on the main thread.
     *
     * <p>Safe because the analyzer is pure and the snapshot is immutable: the async task
     * touches no Bukkit API. Falls back to a synchronous run when {@code diagnostics.async}
     * is off.
     */
    public void analyzeAsync(Consumer<DiagnosticReport> callback) {
        DiagnosticSnapshot snapshot = latest.get();

        if (!async) {
            callback.accept(analyzer.analyze(snapshot, System.currentTimeMillis()));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            DiagnosticReport report = analyzer.analyze(snapshot, System.currentTimeMillis());
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(report));
        });
    }

    /** Builds recommendations from the cached snapshot on the calling thread. */
    public RecommendationReport recommend() {
        if (!recommendationsEnabled) {
            return RecommendationReport.unavailable();
        }
        return recommendationEngine.recommend(latest.get(), System.currentTimeMillis());
    }

    /**
     * Builds recommendations off-thread, then delivers them on the main thread. The engine
     * is pure and never writes configuration, so there is nothing unsafe to do off-thread.
     */
    public void recommendAsync(Consumer<RecommendationReport> callback) {
        if (!recommendationsEnabled) {
            callback.accept(RecommendationReport.unavailable());
            return;
        }

        DiagnosticSnapshot snapshot = latest.get();

        if (!async) {
            callback.accept(recommendationEngine.recommend(snapshot, System.currentTimeMillis()));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            RecommendationReport report =
                    recommendationEngine.recommend(snapshot, System.currentTimeMillis());
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(report));
        });
    }

    public DiagnosticThresholds getThresholds() {
        return thresholds;
    }

    public DiagnosticAnalyzer getAnalyzer() {
        return analyzer;
    }

    public RecommendationEngine getRecommendationEngine() {
        return recommendationEngine;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAsync() {
        return async;
    }

    public boolean areRecommendationsEnabled() {
        return recommendationsEnabled;
    }

    public int getSampleIntervalTicks() {
        return sampleIntervalTicks;
    }

    public void shutdown() {
        latest.set(null);
    }
}
