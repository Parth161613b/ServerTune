package com.servertune.command.serverhealth;

import com.servertune.ServerTunePlugin;
import com.servertune.command.BaseCommand;
import com.servertune.metrics.HealthSnapshot;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;

import java.util.Map;

public class ServerHealthCoreCommand extends BaseCommand implements CommandExecutor {

    private final ServerTunePlugin plugin;

    public ServerHealthCoreCommand(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, args);
    }

    /**
     * The command body, reachable from both registration paths. The Brigadier tree calls this
     * directly; {@code onCommand} delegates to it unchanged.
     */
    public boolean execute(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "serverhealth.core")) {
            return true;
        }

        // Get cached snapshot
        HealthSnapshot snapshot = plugin.getSnapshotCache().getLatest();

        if (snapshot == null) {
            sendError(sender, "§cNo health data available yet. Please wait a moment...");
            return true;
        }

        // Display detailed health information
        sendRaw(sender, "§8§m                    §r §6§lSERVER HEALTH CORE§r §8§m                    §r");
        sendRaw(sender, "");

        // Performance metrics
        sendRaw(sender, "§e§lPerformance:");
        sendRaw(sender, "  §7TPS: §f" + formatDecimal(snapshot.getTps(), 2) + " §8(20.0 max)");
        sendRaw(sender, "  §7MSPT: §f" + formatDecimal(snapshot.getMspt(), 2) + " ms §8(50.0 max)");
        sendRaw(sender, "  §7CPU Usage: §f" + formatPercentage(snapshot.getCpuUsage()) + " §8(JVM process)");
        String statusColor = getStatusColor(snapshot.getServerStatus());
        sendRaw(sender, "  §7Status: " + statusColor + snapshot.getServerStatus());
        sendRaw(sender, "");

        // Memory metrics
        sendRaw(sender, "§e§lMemory:");
        sendRaw(sender, "  §7Used: §f" + formatMemory(snapshot.getMemoryUsed()) + " GB");
        sendRaw(sender, "  §7Allocated: §f" + formatMemory(snapshot.getMemoryAllocated()) + " GB");
        sendRaw(sender, "  §7Max: §f" + formatMemory(snapshot.getMemoryMax()) + " GB");
        double memoryUsagePercent = (snapshot.getMemoryUsed() * 100.0) / snapshot.getMemoryMax();
        sendRaw(sender, "  §7Usage: §f" + formatPercentage(memoryUsagePercent));
        sendRaw(sender, "");

        // Server metrics
        sendRaw(sender, "§e§lServer:");
        sendRaw(sender, "  §7Online Players: §f" + snapshot.getOnlinePlayers() + " §7/§f " + snapshot.getMaxPlayers());
        sendRaw(sender, "  §7Loaded Chunks: §f" + snapshot.getLoadedChunks());
        sendRaw(sender, "  §7Total Entities: §f" + snapshot.getTotalEntities());
        sendRaw(sender, "  §7Loaded Worlds: §f" + snapshot.getLoadedWorlds());
        sendRaw(sender, "  §7Loaded Plugins: §f" + snapshot.getLoadedPlugins());
        if (snapshot.getLoadedDatapacks() > 0) {
            sendRaw(sender, "  §7Loaded Datapacks: §f" + snapshot.getLoadedDatapacks());
        }
        sendRaw(sender, "");

        // Entity breakdown (top 5)
        sendRaw(sender, "§e§lTop Entities:");
        Map<EntityType, Integer> entityCounts = snapshot.getEntityCounts();
        entityCounts.entrySet().stream()
                .sorted(Map.Entry.<EntityType, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(entry -> {
                    sendRaw(sender, "  §7" + formatEntityName(entry.getKey()) + ": §f" + entry.getValue());
                });
        sendRaw(sender, "");

        // Optimizer info
        sendRaw(sender, "§e§lOptimizer:");
        sendRaw(sender, "  §7Profile: §f" + snapshot.getActiveProfile());
        sendRaw(sender, "  §7Modules Enabled: §f" + plugin.getModuleManager().getModuleCount());
        sendRaw(sender, "");

        // Timestamp
        long timeSince = plugin.getSnapshotCache().getTimeSinceUpdate();
        sendRaw(sender, "§8Snapshot taken " + formatTimeSince(timeSince));
        sendRaw(sender, "§8§m                                                      §r");

        return true;
    }

    private String formatEntityName(EntityType type) {
        String name = type.name().toLowerCase().replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }
}
