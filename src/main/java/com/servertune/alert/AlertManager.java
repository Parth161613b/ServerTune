package com.servertune.alert;

import com.servertune.ServerTunePlugin;
import com.servertune.fallback.SustainedThreshold;
import com.servertune.metrics.HealthSnapshot;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class AlertManager {

    private final ServerTunePlugin plugin;

    // TPS alert thresholds
    private SustainedThreshold tpsWarning;
    private SustainedThreshold tpsCritical;
    private SustainedThreshold tpsEmergency;

    // MSPT alert thresholds
    private SustainedThreshold msptWarning;
    private SustainedThreshold msptCritical;

    // Alert cooldowns
    private final Map<AlertType, Long> lastAlertTime = new HashMap<>();
    private long alertCooldownMillis;

    // Per-family switches, cached because checkAlerts runs on every health tick
    private boolean tpsAlertsEnabled;
    private boolean msptAlertsEnabled;

    /**
     * Who receives alerts. Read from the same key the performance guard uses, so an owner who
     * retargets alerts to a custom permission gets both families moving together instead of
     * the guard honouring the setting while these alerts kept going to the built-in node.
     */
    private String alertPermission;

    // Recovery tracking
    private boolean wasInAlert = false;
    private AlertType lastAlertType = null;

    public AlertManager(ServerTunePlugin plugin) {
        this.plugin = plugin;
        loadConfiguration();
    }

    private void loadConfiguration() {
        try {
            // TPS thresholds
            double tpsWarnThreshold = plugin.getConfigManager().getTpsWarningThreshold();
            int tpsWarnSeconds = plugin.getConfig().getInt("alerts.tps.warning.sustained-seconds", 10);
            tpsWarning = new SustainedThreshold(tpsWarnThreshold, tpsWarnSeconds);

            double tpsCritThreshold = plugin.getConfigManager().getTpsCriticalThreshold();
            int tpsCritSeconds = plugin.getConfig().getInt("alerts.tps.critical.sustained-seconds", 5);
            tpsCritical = new SustainedThreshold(tpsCritThreshold, tpsCritSeconds);

            double tpsEmergThreshold = plugin.getConfigManager().getTpsEmergencyThreshold();
            int tpsEmergSeconds = plugin.getConfig().getInt("alerts.tps.emergency.sustained-seconds", 3);
            tpsEmergency = new SustainedThreshold(tpsEmergThreshold, tpsEmergSeconds);

            // MSPT thresholds
            double msptWarnThreshold = plugin.getConfig().getDouble("alerts.mspt.warning.threshold", 40.0);
            int msptWarnSeconds = plugin.getConfig().getInt("alerts.mspt.warning.sustained-seconds", 10);
            msptWarning = new SustainedThreshold(msptWarnThreshold, msptWarnSeconds);

            double msptCritThreshold = plugin.getConfig().getDouble("alerts.mspt.critical.threshold", 50.0);
            int msptCritSeconds = plugin.getConfig().getInt("alerts.mspt.critical.sustained-seconds", 5);
            msptCritical = new SustainedThreshold(msptCritThreshold, msptCritSeconds);

            tpsAlertsEnabled = plugin.getConfig().getBoolean("alerts.tps.enabled", true);
            msptAlertsEnabled = plugin.getConfig().getBoolean("alerts.mspt.enabled", true);

            alertPermission = plugin.getConfig().getString(
                    "performance-guard.actions.alert-permission", "serverhealth.alert");

            // One cooldown governs every alert type, TPS and MSPT alike, so it is read from
            // one key. alerts.mspt.cooldown-seconds was never consulted and has been removed
            // from config.yml rather than left there implying a second, separate cooldown.
            int cooldownSeconds = plugin.getConfig().getInt("alerts.cooldown-seconds",
                    plugin.getConfig().getInt("alerts.tps.cooldown-seconds", 60));
            alertCooldownMillis = cooldownSeconds * 1000L;

            plugin.getLogger().info("Alert system configured:");
            plugin.getLogger().info("  TPS Warning: < " + tpsWarnThreshold + " for " + tpsWarnSeconds + "s");
            plugin.getLogger().info("  TPS Critical: < " + tpsCritThreshold + " for " + tpsCritSeconds + "s");
            plugin.getLogger().info("  TPS Emergency: < " + tpsEmergThreshold + " for " + tpsEmergSeconds + "s");
            plugin.getLogger().info("  Alert cooldown: " + cooldownSeconds + "s");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load alert configuration", e);
        }
    }

    public void checkAlerts(HealthSnapshot snapshot) {
        if (!plugin.getConfigManager().areAlertsEnabled()) {
            return;
        }

        double tps = snapshot.getTps();
        double mspt = snapshot.getMspt();

        // Check TPS alerts (most severe first)
        if (!tpsAlertsEnabled) {
            // Skipped entirely, including the recovery branch: with TPS alerts off there is
            // no TPS alert to recover from.
        } else if (tpsEmergency.checkBelow(tps)) {
            sendAlert(AlertType.TPS_EMERGENCY, String.format(
                    "§c§l[EMERGENCY] §cTPS critically low: %.2f (threshold: %.2f)",
                    tps, tpsEmergency.getThreshold()
            ));
        } else if (tpsCritical.checkBelow(tps)) {
            sendAlert(AlertType.TPS_CRITICAL, String.format(
                    "§6§l[CRITICAL] §6TPS critical: %.2f (threshold: %.2f)",
                    tps, tpsCritical.getThreshold()
            ));
        } else if (tpsWarning.checkBelow(tps)) {
            sendAlert(AlertType.TPS_WARNING, String.format(
                    "§e§l[WARNING] §eTPS below threshold: %.2f (threshold: %.2f)",
                    tps, tpsWarning.getThreshold()
            ));
        } else {
            // No TPS alert - check for recovery
            if (wasInAlert && lastAlertType != null && lastAlertType.name().startsWith("TPS_")) {
                sendRecoveryAlert(String.format(
                        "§a§l[RECOVERY] §aServer performance recovered. TPS: %.2f MSPT: %.2fms",
                        tps, mspt
                ));
                wasInAlert = false;
                lastAlertType = null;
            }
        }

        // Check MSPT alerts
        if (msptAlertsEnabled) {
            if (msptCritical.checkAbove(mspt)) {
                sendAlert(AlertType.MSPT_CRITICAL, String.format(
                        "§6§l[CRITICAL] §6MSPT high: %.2fms (threshold: %.2fms)",
                        mspt, msptCritical.getThreshold()
                ));
            } else if (msptWarning.checkAbove(mspt)) {
                sendAlert(AlertType.MSPT_WARNING, String.format(
                        "§e§l[WARNING] §eMSPT elevated: %.2fms (threshold: %.2fms)",
                        mspt, msptWarning.getThreshold()
                ));
            }
        }
    }

    private void sendAlert(AlertType type, String message) {
        // Check cooldown
        Long lastAlert = lastAlertTime.get(type);
        long now = System.currentTimeMillis();

        if (lastAlert != null && (now - lastAlert) < alertCooldownMillis) {
            // Still in cooldown
            return;
        }

        // Send alert
        dispatchAlert(message);

        // Update tracking
        lastAlertTime.put(type, now);
        wasInAlert = true;
        lastAlertType = type;
    }

    private void sendRecoveryAlert(String message) {
        if (!plugin.getConfig().getBoolean("alerts.recovery.enabled", true)) {
            return;
        }

        if (!plugin.getConfig().getBoolean("alerts.recovery.notify-on-recovery", true)) {
            return;
        }

        dispatchAlert(message);
    }

    private void dispatchAlert(String message) {
        // Send to players with permission
        Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.hasPermission(alertPermission))
                .forEach(player -> player.sendMessage("§8[§6ServerTune§8]§r " + message));

        // Send to console if enabled
        if (plugin.getConfig().getBoolean("alerts.console", true)) {
            plugin.getLogger().warning(message);
        }
    }

    public void reload() {
        loadConfiguration();
        lastAlertTime.clear();
        wasInAlert = false;
        lastAlertType = null;
    }

    public void reset() {
        lastAlertTime.clear();
        wasInAlert = false;
        lastAlertType = null;

        if (tpsWarning != null) tpsWarning.reset();
        if (tpsCritical != null) tpsCritical.reset();
        if (tpsEmergency != null) tpsEmergency.reset();
        if (msptWarning != null) msptWarning.reset();
        if (msptCritical != null) msptCritical.reset();
    }
}
