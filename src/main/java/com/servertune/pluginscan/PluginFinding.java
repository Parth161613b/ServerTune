package com.servertune.pluginscan;

/**
 * One plugin's entry in a finished report: what was observed, how big it looked, how sure we
 * are, and why.
 *
 * <p>The {@code observation} and {@code reason} strings are built by {@link PluginScanRanker}
 * rather than by the renderer, so the wording that makes a claim lives next to the logic that
 * justifies it. A renderer cannot accidentally describe a LOW-confidence correlation as a
 * measurement, because it never composes that sentence itself.
 *
 * @param measurement    the frozen counters this finding was derived from
 * @param impact         how large the observed main-thread contribution looked
 * @param confidence     how much the evidence behind it is worth
 * @param observation    what was actually counted, in one phrase
 * @param reason         why this impact and confidence were assigned
 * @param msptCorrelated whether server MSPT was elevated during the same window; recorded so the
 *                       report can say "coincided with" without the renderer having to infer it
 */
public record PluginFinding(PluginActivity.Measurement measurement,
                            Impact impact,
                            Confidence confidence,
                            String observation,
                            String reason,
                            boolean msptCorrelated) {

    public String pluginName() {
        return measurement.pluginName();
    }

    /**
     * True when this finding is worth an operator's attention. NEGLIGIBLE findings are carried
     * through the pipeline so the report can state how many plugins were measured and found
     * cheap, but they are not "potential performance sources".
     */
    public boolean isSignificant() {
        return impact != Impact.NEGLIGIBLE;
    }

    /**
     * The one action ServerTune is willing to recommend.
     *
     * <p>Deliberately never "disable this plugin". A ten-second observation window on a live
     * server cannot justify removing production functionality, and the plugin that shows the
     * most handler time may simply be the one doing the most useful work. The only sound next
     * step is a controlled A/B test somewhere that breaking does not matter.
     */
    public String recommendation() {
        if (!isSignificant()) {
            return "No action suggested; the measured cost was negligible.";
        }
        return "Temporarily test the server with " + pluginName() + " disabled in a staging "
                + "environment to verify whether MSPT improves.";
    }

    /** How this contributor is described. Never causal - see {@link PluginScanRanker}. */
    public String descriptor() {
        return "potential contributor to main-thread workload";
    }
}
