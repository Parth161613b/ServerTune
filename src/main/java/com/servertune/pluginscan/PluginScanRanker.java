package com.servertune.pluginscan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Turns raw observations into a ranked, honestly-labelled list of findings.
 *
 * <p>Pure and Bukkit-free, so every ranking and confidence decision is unit tested.
 *
 * <h2>What this class refuses to do</h2>
 *
 * <p><b>It does not rank on task count.</b> Owning many tasks is not evidence of cost - a
 * plugin with 100 tasks that each do nothing is cheaper than one task that walks every entity
 * in the world. Task count therefore contributes nothing to {@link #score}; it can only ever
 * produce a LOW-confidence "owns scheduled work" note for a plugin with no timed evidence, and
 * such a plugin always sorts below any plugin that was actually measured.
 *
 * <p><b>It ranks on main-thread time only.</b> Async milliseconds are reported but scored zero.
 * Async work does not delay the tick, and letting it into the score would rank a plugin doing
 * background I/O above one stalling the server.
 *
 * <p><b>It never claims causation.</b> Every finding is phrased as an observation. Elevated MSPT
 * during the window raises the described severity of a plugin that was <em>also</em> measurably
 * expensive, and does nothing at all for a plugin that was not - "MSPT was high and this plugin
 * existed" is not evidence about this plugin.
 *
 * <h2>Impact bands</h2>
 *
 * <p>Bands are fractions of the window's total measured main-thread handler time, not of the
 * tick. ServerTune can measure what fraction of the time <em>it observed</em> belongs to each
 * plugin; it cannot measure what fraction of the whole tick that represents, because it does not
 * time the parts of the tick it did not wrap.
 */
public final class PluginScanRanker {

    /** A plugin needs at least this many sampled calls before an average is called stable. */
    static final long STABLE_SAMPLE_CALLS = 20L;

    /** Share of measured handler time that reads as a HIGH impact contributor. */
    static final double HIGH_SHARE = 0.40;
    static final double MEDIUM_SHARE = 0.15;

    /** Absolute floor: below this a plugin is noise however large its share of a quiet window. */
    static final double MIN_SIGNIFICANT_MILLIS = 1.0;

    private final PluginScanSettings settings;

    public PluginScanRanker(PluginScanSettings settings) {
        this.settings = settings == null ? PluginScanSettings.defaults() : settings;
    }

    /**
     * Ranks every measurement and keeps the top {@code top-results} that meet the configured
     * minimum confidence.
     *
     * @param measurements  one entry per plugin observed during the window
     * @param msptElevated  whether the window's average MSPT was above the elevated threshold;
     *                      used only to sharpen the wording for plugins that were already
     *                      measurably expensive
     */
    public List<PluginFinding> rank(List<PluginActivity.Measurement> measurements,
                                    boolean msptElevated) {
        if (measurements == null || measurements.isEmpty()) {
            return List.of();
        }

        double totalSyncMillis = 0.0;
        for (PluginActivity.Measurement m : measurements) {
            totalSyncMillis += m.syncMillis();
        }

        List<PluginFinding> findings = new ArrayList<>(measurements.size());
        for (PluginActivity.Measurement m : measurements) {
            PluginFinding finding = classify(m, totalSyncMillis, msptElevated);
            if (finding != null && finding.confidence().atLeast(settings.minimumConfidence())) {
                findings.add(finding);
            }
        }

        // Timed evidence first, then measured cost, then peak. Task-only findings sort last
        // because they carry no cost measurement to compare.
        findings.sort(Comparator
                .comparing((PluginFinding f) -> f.measurement().hasTimedEvidence())
                .thenComparingDouble(f -> f.measurement().syncMillis())
                .thenComparingDouble(f -> f.measurement().peakSyncMillis())
                .reversed()
                .thenComparing(f -> f.measurement().pluginName()));

        int limit = Math.min(settings.topResults(), findings.size());
        return List.copyOf(findings.subList(0, limit));
    }

    /**
     * Classifies one plugin, or returns null when there is nothing worth reporting.
     */
    private PluginFinding classify(PluginActivity.Measurement m, double totalSyncMillis,
                                   boolean msptElevated) {
        if (m.hasTimedEvidence()) {
            return classifyTimed(m, totalSyncMillis, msptElevated);
        }

        // No timed evidence. The only thing left is inventory, which is not a measurement.
        if (m.totalTaskCount() > 0) {
            return new PluginFinding(m, Impact.LOW, Confidence.LOW,
                    "owns " + m.totalTaskCount() + " scheduled task(s); no measurable "
                            + "main-thread handler activity was observed",
                    "Task ownership alone is not evidence of cost. This plugin's scheduled work "
                            + "was never observed running expensively during the window.",
                    false);
        }

        // Observed doing nothing at all. Not a finding.
        return null;
    }

    private PluginFinding classifyTimed(PluginActivity.Measurement m, double totalSyncMillis,
                                        boolean msptElevated) {
        double share = totalSyncMillis <= 0.0 ? 0.0 : m.syncMillis() / totalSyncMillis;
        boolean significant = m.syncMillis() >= MIN_SIGNIFICANT_MILLIS;

        Impact impact;
        if (significant && share >= HIGH_SHARE) {
            impact = Impact.HIGH;
        } else if (significant && share >= MEDIUM_SHARE) {
            impact = Impact.MEDIUM;
        } else if (significant) {
            impact = Impact.LOW;
        } else {
            impact = Impact.NEGLIGIBLE;
        }

        // Direct timing is direct timing; the only question is whether the sample is thick
        // enough for the average to mean anything.
        Confidence confidence = m.syncCalls() >= STABLE_SAMPLE_CALLS
                ? Confidence.HIGH
                : Confidence.MEDIUM;

        String observation = String.format(Locale.ROOT,
                "%,d timed main-thread event handler call(s) totalling %.2f ms (%.0f%% of all "
                        + "measured handler time)", m.syncCalls(), m.syncMillis(), share * 100.0);

        StringBuilder reason = new StringBuilder();
        reason.append(confidence == Confidence.HIGH
                ? "Measured directly by timing this plugin's own event handlers over "
                : "Measured directly by timing this plugin's own event handlers, but over only ");
        reason.append(String.format(Locale.ROOT, "%,d call(s)", m.syncCalls()));
        if (confidence == Confidence.MEDIUM) {
            reason.append(", which is a thin sample - the average may not be stable");
        }
        reason.append('.');

        // Correlation is allowed to sharpen the wording for a plugin that was already
        // expensive. It is never allowed to create a finding on its own.
        boolean correlated = msptElevated && impact != Impact.NEGLIGIBLE;
        if (correlated) {
            reason.append(" Server MSPT was elevated during the same window, so this activity "
                    + "coincided with the slowdown; that is correlation, not proof of cause.");
        }

        if (m.asyncCalls() > 0L) {
            reason.append(String.format(Locale.ROOT,
                    " Also ran %,d asynchronous handler call(s) totalling %.2f ms, which do not "
                            + "delay the tick and are excluded from the ranking.",
                    m.asyncCalls(), m.asyncMillis()));
        }

        return new PluginFinding(m, impact, confidence, observation, reason.toString(), correlated);
    }

    /**
     * The score used for nothing but tests and diagnostics of the ranker itself: measured
     * main-thread milliseconds. Kept as a named method so the "task count is not a score"
     * property is explicit and testable rather than implied.
     */
    public static double score(PluginActivity.Measurement m) {
        return m.syncMillis();
    }
}
