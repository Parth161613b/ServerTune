package com.servertune.pluginscan;

/**
 * How much the evidence behind a finding is worth.
 *
 * <p>This is a statement about <em>measurement quality</em>, not about severity. A plugin can
 * be a HIGH impact contributor with LOW confidence (something was clearly expensive, but the
 * only evidence is correlation) or a LOW impact contributor with HIGH confidence (we timed it
 * precisely and it was cheap). The two axes are reported separately for exactly that reason.
 *
 * <p>What earns each level in this implementation is fixed and narrow, because the alternative
 * is confidence inflation:
 * <ul>
 *   <li>{@link #HIGH} - the number came from a timer around that plugin's own event handler.
 *       ServerTune started the clock, the plugin's code ran, ServerTune stopped the clock. The
 *       attribution is direct.</li>
 *   <li>{@link #MEDIUM} - direct timing exists but the sample is thin (few executions), so the
 *       average is real but not yet stable.</li>
 *   <li>{@link #LOW} - no direct timing at all. The plugin owns scheduled tasks, or was merely
 *       present while MSPT was elevated. This is correlation and is labelled as such.</li>
 * </ul>
 */
public enum Confidence {

    LOW,
    MEDIUM,
    HIGH;

    /** True when this level is at least as strong as {@code minimum}. */
    public boolean atLeast(Confidence minimum) {
        return minimum == null || ordinal() >= minimum.ordinal();
    }
}
