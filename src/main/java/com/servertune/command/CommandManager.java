package com.servertune.command;

import com.servertune.ServerTunePlugin;
import com.servertune.command.optimizer.OptimizerCommand;
import com.servertune.command.serverhealth.ServerHealthCommand;
import com.servertune.command.serverhealth.ServerHealthCoreCommand;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

public class CommandManager {

    private final ServerTunePlugin plugin;

    public CommandManager(ServerTunePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the three command trees through Paper's Brigadier registrar.
     *
     * <p>Registration moved off {@code PluginCommand#setExecutor} because that path has no way
     * to describe subcommands to the client: it can only accept a raw argument array, which is
     * why 1.0.0 shipped without tab completion. The Brigadier tree declares the subcommands, so
     * the client can complete them and the server can hide the ones a sender may not run.
     *
     * <p>The handlers are constructed once, here, and shared with the tree. They keep per-player
     * state - the live view holds a task per viewer - so rebuilding them on the reload firing of
     * this event would orphan running tasks.
     */
    public void registerCommands() {
        BrigadierCommands trees = new BrigadierCommands(
                new ServerHealthCommand(plugin),
                new ServerHealthCoreCommand(plugin),
                new OptimizerCommand(plugin));

        plugin.getLifecycleManager().registerEventHandler(
                LifecycleEvents.COMMANDS,
                event -> trees.register(event.registrar()));

        plugin.getLogger().info("Commands registered successfully");
    }
}
