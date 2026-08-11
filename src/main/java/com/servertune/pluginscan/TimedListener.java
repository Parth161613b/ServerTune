package com.servertune.pluginscan;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.plugin.RegisteredListener;

/**
 * A {@link RegisteredListener} that times the one it replaced.
 *
 * <p>This is the whole measurement mechanism, and it is the only place in ServerTune that
 * touches another plugin's registration. {@code RegisteredListener} is a non-final public class
 * with a public {@code callEvent}, so subclassing it and delegating is a supported use of the
 * public Paper API - no reflection, no internals, no deprecated Timings.
 *
 * <h2>Why the delegate is re-created rather than held</h2>
 *
 * <p>The five things that define a registration - listener, executor, priority, owning plugin,
 * ignore-cancelled - are all readable from the original through public getters and are all
 * accepted by the public constructor. So this subclass <em>is</em> the original registration plus
 * a timer; {@code super.callEvent} runs exactly the code the server would have run. The original
 * object is kept only so teardown can put it back byte-for-byte identical.
 *
 * <h2>Cost on the hot path</h2>
 *
 * <p>Two {@code System.nanoTime()} reads, one {@code isPrimaryThread()} check and one atomic add
 * per handler call. Nothing is allocated, nothing is logged and nothing is looked up in a map -
 * the {@link PluginActivity} reference is resolved once at wrap time and captured in a field.
 *
 * <p>A call the server would have skipped is not counted. When {@code ignoreCancelled} is set and
 * the event is already cancelled, {@code super.callEvent} returns without running the handler;
 * recording that would add a call of near-zero duration to the plugin's average and make a busy
 * cancelled event look like cheap activity. The check below mirrors the one inside
 * {@code RegisteredListener} using public API only.
 */
final class TimedListener extends RegisteredListener {

    private final RegisteredListener original;
    private final PluginActivity activity;

    TimedListener(RegisteredListener original, PluginActivity activity) {
        super(original.getListener(), original.getExecutor(), original.getPriority(),
                original.getPlugin(), original.isIgnoringCancelled());
        this.original = original;
        this.activity = activity;
    }

    /** The registration this wrapper stands in for, so teardown can restore the exact object. */
    RegisteredListener original() {
        return original;
    }

    @Override
    public void callEvent(Event event) throws EventException {
        if (isIgnoringCancelled() && event instanceof Cancellable cancellable
                && cancellable.isCancelled()) {
            super.callEvent(event);
            return;
        }

        boolean mainThread = Bukkit.isPrimaryThread();
        long start = System.nanoTime();
        try {
            super.callEvent(event);
        } finally {
            // Recorded in a finally block so a handler that throws is still charged for the
            // time it burned. Swallowing the cost of a failing plugin would flatter it.
            activity.record(System.nanoTime() - start, mainThread);
        }
    }
}
