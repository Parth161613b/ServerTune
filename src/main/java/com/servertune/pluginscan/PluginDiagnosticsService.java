package com.servertune.pluginscan;

import com.servertune.ServerTunePlugin;
import com.servertune.metrics.HealthSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Owns the one plugin diagnostic that may be running, and nothing else.
 *
 * <h2>Dormancy</h2>
 *
 * <p>Constructing this class starts nothing. No task, no listener, no timer, no reference to any
 * other plugin. Until {@link #start} is called the object is a null {@code session} field and a
 * settings record, which is what makes the "zero overhead when unused" requirement true by
 * construction rather than by careful configuration.
 *
 * <p>Everything the service creates while running is torn down by {@link #stop}: the ticker task
 * is cancelled, every wrapped listener is restored to the original object, and the session's
 * accumulators are cleared so no foreign plugin name or activity counter is still reachable.
 *
 * <h2>Threading</h2>
 *
 * <p>Install, restore and the ticker all run on the main thread, because they mutate handler
 * lists and read server state. The only thing that touches this class off the main thread is
 * {@link PluginActivity#record}, from async event handlers, and that path is atomic counters
 * only.
 */
public final class PluginDiagnosticsService {

    /** The scheduler task name, in the project's existing named-task convention. */
    public static final String TICKER_TASK = "plugin-scan-ticker";

    /** How often the ticker runs. One second: fine enough for a hard timeout, cheap enough to ignore. */
    private static final long TICK_PERIOD = 20L;

    private final ServerTunePlugin plugin;

    private volatile PluginScanSession session;
    private volatile PluginScanProfiler profiler;
    private volatile CommandSender requester;
    private volatile int lastProgressSecond = -1;

    public PluginDiagnosticsService(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    /** Whether a diagnostic is currently running. */
    public boolean isRunning() {
        PluginScanSession current = session;
        return current != null && current.isRunning();
    }

    public PluginScanSettings settings() {
        return plugin.getConfigManager().getPluginScanSettings();
    }

    /**
     * Starts a window, or explains why it cannot.
     *
     * @return the outcome to report to the sender; the service reports the run itself
     */
    public StartResult start(CommandSender sender, int requestedSeconds) {
        PluginScanSettings settings = settings();

        if (!settings.enabled()) {
            return StartResult.DISABLED;
        }
        if (isRunning()) {
            return StartResult.ALREADY_RUNNING;
        }
        // Profiling a server that is already collapsing makes both problems worse, and the
        // measurement would be dominated by the emergency rather than by any plugin.
        if (plugin.getPerformanceGuard().isInFallback()) {
            return StartResult.SERVER_CRITICAL;
        }

        PluginScanSession started = new PluginScanSession(settings, requestedSeconds,
                System::nanoTime);
        PluginScanProfiler installed = new PluginScanProfiler(plugin);

        int wrapped;
        long overheadStart = System.nanoTime();
        try {
            wrapped = installed.install(started);
        } catch (RuntimeException e) {
            // Leave nothing half-wrapped if install failed partway through.
            installed.restore();
            plugin.getLogger().log(Level.SEVERE, "Failed to start plugin diagnostic", e);
            return StartResult.FAILED;
        }
        started.addOverheadNanos(System.nanoTime() - overheadStart);

        started.setInventory(countInstalled(), countEnabled(),
                PluginScanProfiler.instrumentedEventCount());
        sampleInto(started);

        this.session = started;
        this.profiler = installed;
        this.requester = sender;
        this.lastProgressSecond = -1;

        plugin.getScheduler().scheduleTask(TICKER_TASK, this::tick, TICK_PERIOD, TICK_PERIOD);

        plugin.getLogger().info("Plugin diagnostic started for " + started.durationSeconds()
                + "s; wrapped " + wrapped + " listener registration(s) across "
                + PluginScanProfiler.instrumentedEventCount() + " event type(s)");
        return StartResult.STARTED;
    }

    /** Cancels a running diagnostic. Returns false when there was nothing to cancel. */
    public boolean cancel() {
        PluginScanSession current = session;
        if (current == null || !current.cancel()) {
            return false;
        }
        finish(current);
        return true;
    }

    /**
     * Called from the plugin's own shutdown. Restores everything without trying to render a
     * report to a sender who is probably already gone.
     */
    public void shutdown() {
        PluginScanSession current = session;
        if (current == null) {
            return;
        }
        current.cancel();
        teardown();
        current.clear();
        session = null;
        requester = null;
    }

    /** The one-second heartbeat: progress, hard timeout, fallback abort. */
    private void tick() {
        PluginScanSession current = session;
        if (current == null || !current.isRunning()) {
            return;
        }

        long overheadStart = System.nanoTime();

        if (plugin.getPerformanceGuard().isInFallback()) {
            current.addOverheadNanos(System.nanoTime() - overheadStart);
            if (current.abortCritical()) {
                finish(current);
            }
            return;
        }

        sampleInto(current);

        int elapsed = current.elapsedWholeSeconds();
        int interval = settings().progressIntervalSeconds();
        if (elapsed > 0 && elapsed % interval == 0 && elapsed != lastProgressSecond
                && elapsed < current.durationSeconds()) {
            lastProgressSecond = elapsed;
            send(PluginScanFormatter.progressLine(elapsed, current.durationSeconds()));
        }

        current.addOverheadNanos(System.nanoTime() - overheadStart);

        // Checked here rather than trusted to a delayed task, so a lagging or rescheduled tick
        // cannot let the window run past its ceiling.
        if (current.isExpired() && current.complete()) {
            finish(current);
        }
    }

    /**
     * The single exit path. Stops profiling first, then reports, then releases - in that order,
     * so nothing is still being measured while the report is rendered.
     */
    private void finish(PluginScanSession finished) {
        teardown();

        PluginScanReport report = finished.buildReport(new PluginScanRanker(settings()));
        finished.clear();
        session = null;

        for (String line : PluginScanFormatter.render(report)) {
            send(line);
        }
        requester = null;

        plugin.getLogger().info("Plugin diagnostic " + report.outcome() + " after "
                + String.format("%.1f", report.elapsedSeconds()) + "s; "
                + report.significantFindings().size() + " potential source(s) reported");
    }

    /** Cancels the ticker and restores every wrapped registration. Idempotent. */
    private void teardown() {
        plugin.getScheduler().cancelTask(TICKER_TASK);

        PluginScanProfiler current = profiler;
        if (current != null) {
            current.restore();
            profiler = null;
        }
    }

    /**
     * Folds one health sample into the session, reusing the existing snapshot cache. No second
     * health monitor is created; if the cache has nothing yet the sample is skipped rather than
     * measured independently.
     */
    private void sampleInto(PluginScanSession target) {
        HealthSnapshot snapshot = plugin.getSnapshotCache().getLatest();
        if (snapshot == null) {
            return;
        }
        target.recordSample(snapshot.getTps(), snapshot.getMspt(), snapshot.getOnlinePlayers(),
                snapshot.getLoadedChunks(), snapshot.getTotalEntities());
    }

    private int countInstalled() {
        return Bukkit.getPluginManager().getPlugins().length;
    }

    private int countEnabled() {
        int enabled = 0;
        for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
            if (other.isEnabled()) {
                enabled++;
            }
        }
        return enabled;
    }

    /** Sends to the requester when they are still around, and always to the console. */
    private void send(String line) {
        CommandSender target = requester;
        if (target != null) {
            target.sendMessage(line);
        }
        if (!(target instanceof org.bukkit.command.ConsoleCommandSender)) {
            Bukkit.getConsoleSender().sendMessage(line);
        }
    }

    /** Why a start attempt did or did not happen. */
    public enum StartResult {
        STARTED,
        ALREADY_RUNNING,
        DISABLED,
        SERVER_CRITICAL,
        FAILED
    }

    /** Diagnostic state for {@code /serverhealth debug}, so dormancy is observable. */
    public List<String> debugLines() {
        List<String> out = new ArrayList<>(2);
        PluginScanSession current = session;
        if (current == null) {
            out.add("Plugin scan: dormant (no session, no listeners wrapped, no task)");
            return out;
        }
        PluginScanProfiler current2 = profiler;
        out.add("Plugin scan: " + current.state() + " " + current.elapsedWholeSeconds() + "/"
                + current.durationSeconds() + "s, "
                + (current2 == null ? 0 : current2.installedCount()) + " listener(s) wrapped");
        out.add("Plugin scan guard state: " + plugin.getPerformanceGuard().getCurrentState());
        return out;
    }
}
