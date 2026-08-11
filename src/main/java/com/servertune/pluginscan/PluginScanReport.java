package com.servertune.pluginscan;

import java.util.List;

/**
 * A finished diagnostic, frozen. Bukkit-free so report generation is unit tested.
 *
 * <p>Every field here is something the implementation can actually produce. There is no
 * "cpu percent per plugin" and no "percent of tick" field, because neither is measurable through
 * the public Paper API - see {@link PluginScanRanker}. The report carries its own limitations
 * ({@link #instrumentedEventCount}, {@link #unmeasuredNote}, {@link #overheadMillis}) so a
 * renderer states them without having to know how the profiler works.
 */
public record PluginScanReport(Outcome outcome,
                               double elapsedSeconds,
                               int requestedSeconds,
                               double averageTps,
                               double averageMspt,
                               double peakMspt,
                               int onlinePlayers,
                               int loadedChunks,
                               int loadedEntities,
                               int pluginsInstalled,
                               int pluginsEnabled,
                               int instrumentedEventCount,
                               int measuredPluginCount,
                               double overheadMillis,
                               List<PluginFinding> findings) {

    /** How the session ended. Every ending stops profiling; only the wording differs. */
    public enum Outcome {

        /** Ran the full window and produced a report. */
        COMPLETED,

        /** An administrator ran {@code diagnose plugins cancel}. */
        CANCELLED,

        /** Server performance became critical while profiling; ServerTune bailed out. */
        ABORTED_CRITICAL
    }

    public PluginScanReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** The findings worth calling potential performance sources. */
    public List<PluginFinding> significantFindings() {
        return findings.stream().filter(PluginFinding::isSignificant).toList();
    }

    /**
     * True when nothing rose above the noise floor. Drives the §15 wording, which must say what
     * was <em>not</em> proven as clearly as what was.
     */
    public boolean isEmpty() {
        return significantFindings().isEmpty();
    }

    /**
     * The standing caveat attached to every report. Wrapping a chosen set of hot event types is
     * what keeps the profiler's own cost small; the price is that anything outside that set was
     * never timed, and silence about a plugin is not evidence in its favour.
     */
    public String unmeasuredNote() {
        return "Measured " + instrumentedEventCount + " high-frequency event type(s) only. "
                + "Scheduled task bodies, commands, and other event types were not timed, so a "
                + "plugin absent from this list was not cleared - it was not measured.";
    }

    /** True when the profiler's own measured cost is large enough to admit to (§23). */
    public boolean overheadWorthReporting() {
        return overheadMillis >= 1.0;
    }

    public static Builder builder(Outcome outcome) {
        return new Builder(outcome);
    }

    /** Mirrors the project's existing snapshot builders. */
    public static final class Builder {

        private final Outcome outcome;
        private double elapsedSeconds;
        private int requestedSeconds;
        private double averageTps = 20.0;
        private double averageMspt;
        private double peakMspt;
        private int onlinePlayers;
        private int loadedChunks;
        private int loadedEntities;
        private int pluginsInstalled;
        private int pluginsEnabled;
        private int instrumentedEventCount;
        private int measuredPluginCount;
        private double overheadMillis;
        private List<PluginFinding> findings = List.of();

        private Builder(Outcome outcome) {
            this.outcome = outcome == null ? Outcome.COMPLETED : outcome;
        }

        public Builder elapsedSeconds(double value) {
            this.elapsedSeconds = value;
            return this;
        }

        public Builder requestedSeconds(int value) {
            this.requestedSeconds = value;
            return this;
        }

        public Builder tick(double avgTps, double avgMspt, double peak) {
            this.averageTps = avgTps;
            this.averageMspt = avgMspt;
            this.peakMspt = peak;
            return this;
        }

        public Builder world(int players, int chunks, int entities) {
            this.onlinePlayers = players;
            this.loadedChunks = chunks;
            this.loadedEntities = entities;
            return this;
        }

        public Builder plugins(int installed, int enabled) {
            this.pluginsInstalled = installed;
            this.pluginsEnabled = enabled;
            return this;
        }

        public Builder instrumentedEventCount(int value) {
            this.instrumentedEventCount = value;
            return this;
        }

        public Builder measuredPluginCount(int value) {
            this.measuredPluginCount = value;
            return this;
        }

        public Builder overheadMillis(double value) {
            this.overheadMillis = value;
            return this;
        }

        public Builder findings(List<PluginFinding> value) {
            this.findings = value;
            return this;
        }

        public PluginScanReport build() {
            return new PluginScanReport(outcome, elapsedSeconds, requestedSeconds, averageTps,
                    averageMspt, peakMspt, onlinePlayers, loadedChunks, loadedEntities,
                    pluginsInstalled, pluginsEnabled, instrumentedEventCount, measuredPluginCount,
                    overheadMillis, findings);
        }
    }
}
