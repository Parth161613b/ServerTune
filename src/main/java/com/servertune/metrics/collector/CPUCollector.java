package com.servertune.metrics.collector;

import com.servertune.ServerTunePlugin;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

public class CPUCollector {

    private final ServerTunePlugin plugin;
    private final OperatingSystemMXBean osBean;

    /**
     * Cleared the first time a reading cannot be taken. Reporting reads it to tell "the JVM will
     * not tell us" apart from "the process is idle", which both used to surface as 0.0%.
     */
    private volatile boolean available = true;

    /** Guards the one-time log, so a per-sample failure cannot become a log storm. */
    private volatile boolean unavailableLogged = false;

    public CPUCollector(ServerTunePlugin plugin) {
        this.plugin = plugin;

        OperatingSystemMXBean bean = null;
        try {
            bean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        } catch (Exception e) {
            // Non-HotSpot JVMs do not implement com.sun.management.OperatingSystemMXBean.
            plugin.getLogger().warning("Failed to initialize CPU collector: " + e.getMessage());
        }

        this.osBean = bean;
        if (bean == null) {
            markUnavailable("this JVM does not expose com.sun.management.OperatingSystemMXBean");
        }
    }

    /**
     * Returns JVM process CPU usage as a percentage (0-100), or 0.0 when no reading is available.
     * Note: this is the CPU usage of the Java process running the Minecraft server, not overall
     * system CPU usage.
     *
     * <p>Callers that need to distinguish "unavailable" from "idle" must check
     * {@link #isAvailable()} - a 0.0 return alone cannot tell them apart.
     */
    public double getCPU() {
        if (osBean == null) {
            return 0.0;
        }

        try {
            // getProcessCpuLoad returns 0.0-1.0, or a negative value when unavailable - which some
            // platforms return permanently, and which used to be reported as a flat 0% idle.
            double load = osBean.getProcessCpuLoad();
            if (load < 0) {
                markUnavailable("getProcessCpuLoad() returned " + load
                        + ", which this platform uses to mean 'no reading'");
                return 0.0;
            }

            available = true;
            return Math.min(load * 100.0, 100.0);

        } catch (Exception e) {
            markUnavailable("getProcessCpuLoad() failed: " + e.getMessage());
            return 0.0;
        }
    }

    /** False once a reading has failed; a 0.0 from {@link #getCPU()} is then "unknown", not "idle". */
    public boolean isAvailable() {
        return available;
    }

    private void markUnavailable(String reason) {
        available = false;
        if (!unavailableLogged) {
            unavailableLogged = true;
            plugin.getLogger().warning("CPU usage is unavailable on this JVM (" + reason
                    + "). It will be reported as 0.0% - treat it as unknown, not idle.");
        }
    }
}
