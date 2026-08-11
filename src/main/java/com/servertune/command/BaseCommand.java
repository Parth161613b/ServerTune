package com.servertune.command;

import org.bukkit.command.CommandSender;

public abstract class BaseCommand {

    protected static final String PREFIX = "§8[§6ServerTune§8]§r ";
    protected static final String ERROR_PREFIX = "§8[§cServerTune§8]§r ";

    protected void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + message);
    }

    protected void sendError(CommandSender sender, String message) {
        sender.sendMessage(ERROR_PREFIX + message);
    }

    protected void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(message);
    }

    protected boolean checkPermission(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            sendError(sender, "§cYou don't have permission to use this command.");
            return false;
        }
        return true;
    }

    protected String formatMemory(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        return String.format("%.2f", gb);
    }

    protected String formatPercentage(double value) {
        return String.format("%.1f%%", value);
    }

    protected String formatDecimal(double value, int decimals) {
        return String.format("%." + decimals + "f", value);
    }

    protected String getStatusColor(String status) {
        return switch (status) {
            case "HEALTHY" -> "§a";
            case "GOOD" -> "§2";
            case "WARNING" -> "§e";
            case "CRITICAL" -> "§6";
            case "EMERGENCY" -> "§c";
            default -> "§7";
        };
    }

    protected String formatTimeSince(long milliseconds) {
        long seconds = milliseconds / 1000;
        if (seconds < 60) {
            return seconds + "s ago";
        } else if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        } else {
            return (seconds / 3600) + "h ago";
        }
    }
}
