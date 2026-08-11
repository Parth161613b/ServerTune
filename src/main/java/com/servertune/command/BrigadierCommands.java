package com.servertune.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.servertune.command.optimizer.OptimizerCommand;
import com.servertune.command.serverhealth.ServerHealthCommand;
import com.servertune.command.serverhealth.ServerHealthCoreCommand;
import com.servertune.config.PerformanceProfile;
import com.servertune.pluginscan.PluginScanSettings;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Builds the Brigadier command trees for the three ServerTune commands.
 *
 * <p>The trees exist for one reason: tab completion. Every leaf hands the same
 * {@code (sender, args)} pair to the same handler the {@code CommandExecutor} path always
 * used, so what a subcommand <em>does</em> is unchanged - only how the client learns it
 * exists.
 *
 * <p>Two properties are deliberate.
 *
 * <p><b>Suggestions cost nothing.</b> A completion request walks literal nodes and, at most,
 * calls {@code hasPermission} and reads {@link PerformanceProfile#values()}. Nothing here
 * touches a world, an entity, a chunk, the tick loop or the config file on disk. Completion
 * runs on the main thread for every keystroke, so a suggestion provider that sampled anything
 * would be the tick cost this plugin exists to find.
 *
 * <p><b>Unknown input still reaches the handler.</b> Each root ends in a catch-all word
 * argument that suggests nothing. Without it Brigadier would reject an unrecognised
 * subcommand itself, and two existing behaviours would be lost: the {@code recommend} alias
 * for {@code recommendations}, and the handler's own "Available: ..." reply, which is more
 * use than a red parse error.
 */
public final class BrigadierCommands {

    private static final String DESC_HEALTH = "View server health overview";
    private static final String DESC_CORE = "View detailed server metrics";
    private static final String DESC_OPTIMIZER = "Optimizer management commands";

    /**
     * The available profiles come from the enum the configuration system parses into, so
     * adding a profile there adds it here with no second list to keep in step.
     */
    private static final SuggestionProvider<CommandSourceStack> PROFILE_SUGGESTIONS = (ctx, builder) -> {
        String remaining = builder.getRemainingLowerCase();
        for (PerformanceProfile profile : PerformanceProfile.values()) {
            String name = profile.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    };

    private final ServerHealthCommand health;
    private final ServerHealthCoreCommand core;
    private final OptimizerCommand optimizer;

    public BrigadierCommands(ServerHealthCommand health,
                             ServerHealthCoreCommand core,
                             OptimizerCommand optimizer) {
        this.health = health;
        this.core = core;
        this.optimizer = optimizer;
    }

    /**
     * Registers all three trees against the registrar handed out by the COMMANDS lifecycle
     * event. Safe to run more than once: the event re-fires on a server reload and the
     * handlers are reused rather than rebuilt, so per-player state such as an open live view
     * survives re-registration.
     */
    public void register(Commands commands) {
        commands.register(buildServerHealth(), DESC_HEALTH, List.of("sh", "health"));
        commands.register(buildServerHealthCore(), DESC_CORE, List.of("shc", "healthcore"));
        // "servertune" is an alias rather than a fourth tree: /servertune debug selfmonitor and
        // /optimizer debug selfmonitor have to be the same command, or the permission gate and
        // the runtime state would have two front doors to keep in step.
        commands.register(buildOptimizer(), DESC_OPTIMIZER,
                List.of("opt", "optimize", "servertune", "st"));
    }

    private LiteralCommandNode<CommandSourceStack> buildServerHealth() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("serverhealth")
                .requires(permission("serverhealth.use"))
                .executes(ctx -> dispatch(ctx, health::execute));

        // Read-only views, covered by serverhealth.use alone.
        for (String name : List.of("version", "chunks", "entities", "plugins", "datapacks")) {
            root.then(Commands.literal(name)
                    .executes(ctx -> dispatch(ctx, health::execute, name)));
        }

        // Gated exactly as the handlers gate themselves, so a sender who would be refused on
        // execution is never offered the subcommand in the first place.
        for (String name : List.of("diagnose", "hotspots")) {
            root.then(Commands.literal(name)
                    .requires(permission("serverhealth.diagnose"))
                    .executes(ctx -> dispatch(ctx, health::execute, name)));
        }

        root.then(Commands.literal("debug")
                .requires(permission("serverhealth.admin"))
                .executes(ctx -> dispatch(ctx, health::execute, "debug")));

        root.then(Commands.literal("live")
                .executes(ctx -> dispatch(ctx, health::execute, "live"))
                .then(Commands.literal("stop")
                        .executes(ctx -> dispatch(ctx, health::execute, "live", "stop")))
                .then(Commands.argument("action", StringArgumentType.word())
                        .executes(ctx -> dispatch(ctx, health::execute, "live",
                                StringArgumentType.getString(ctx, "action")))));

        return root.then(unknownSubcommand(health::execute)).build();
    }

    private LiteralCommandNode<CommandSourceStack> buildServerHealthCore() {
        // No subcommands: the command takes no arguments and never has.
        return Commands.literal("serverhealthcore")
                .requires(permission("serverhealth.core"))
                .executes(ctx -> dispatch(ctx, core::execute))
                .build();
    }

    private LiteralCommandNode<CommandSourceStack> buildOptimizer() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("optimizer")
                .requires(permission("optimizer.use"))
                .executes(ctx -> dispatch(ctx, optimizer::execute));

        for (String name : List.of("status", "version", "chunks", "entities")) {
            root.then(Commands.literal(name)
                    .executes(ctx -> dispatch(ctx, optimizer::execute, name)));
        }

        root.then(Commands.literal("reload")
                .requires(permission("optimizer.reload"))
                .executes(ctx -> dispatch(ctx, optimizer::execute, "reload")));
        root.then(Commands.literal("recommendations")
                .requires(permission("optimizer.recommendations"))
                .executes(ctx -> dispatch(ctx, optimizer::execute, "recommendations")));
        // diagnose reads the periodic sample. Its "plugins" child is the one node in the whole
        // tree that starts a measurement, so it carries its own requirement rather than
        // inheriting the parent's: a sender with optimizer.diagnose but not
        // optimizer.diagnose.plugins sees "diagnose" and does not see "plugins" under it.
        //
        // Brigadier ANDs a child's requires() with its parent's, so the plugins branch needs
        // both nodes. That is intended - it is a diagnose subcommand.
        root.then(Commands.literal("diagnose")
                .requires(permission("optimizer.diagnose"))
                .executes(ctx -> dispatch(ctx, optimizer::execute, "diagnose"))
                .then(Commands.literal("plugins")
                        .requires(permission("optimizer.diagnose.plugins"))
                        .executes(ctx -> dispatch(ctx, optimizer::execute, "diagnose", "plugins"))
                        .then(Commands.literal("cancel")
                                .executes(ctx -> dispatch(ctx, optimizer::execute,
                                        "diagnose", "plugins", "cancel")))
                        // An explicit window, clamped by the handler and reported when clamped.
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(
                                        PluginScanSettings.MIN_DURATION_SECONDS,
                                        PluginScanSettings.HARD_MAX_DURATION_SECONDS))
                                .executes(ctx -> dispatch(ctx, optimizer::execute, "diagnose",
                                        "plugins",
                                        String.valueOf(IntegerArgumentType.getInteger(ctx, "seconds")))))));

        // debug, and under it the SelfMonitor runtime logging switch. Every node below inherits
        // the optimizer.admin requirement from its parent, so a sender without it is offered
        // none of them and Brigadier refuses the whole branch - the handler checks the same
        // permission again for anyone arriving by another route.
        root.then(Commands.literal("debug")
                .requires(permission("optimizer.admin"))
                .executes(ctx -> dispatch(ctx, optimizer::execute, "debug"))
                .then(Commands.literal("selfmonitor")
                        .executes(ctx -> dispatch(ctx, optimizer::execute, "debug", "selfmonitor"))
                        .then(Commands.literal("on")
                                .executes(ctx -> dispatch(ctx, optimizer::execute,
                                        "debug", "selfmonitor", "on")))
                        .then(Commands.literal("off")
                                .executes(ctx -> dispatch(ctx, optimizer::execute,
                                        "debug", "selfmonitor", "off")))
                        .then(Commands.literal("status")
                                .executes(ctx -> dispatch(ctx, optimizer::execute,
                                        "debug", "selfmonitor", "status")))
                        // verbose alone toggles; the explicit forms let a script be idempotent.
                        .then(Commands.literal("verbose")
                                .executes(ctx -> dispatch(ctx, optimizer::execute,
                                        "debug", "selfmonitor", "verbose"))
                                .then(Commands.literal("on")
                                        .executes(ctx -> dispatch(ctx, optimizer::execute,
                                                "debug", "selfmonitor", "verbose", "on")))
                                .then(Commands.literal("off")
                                        .executes(ctx -> dispatch(ctx, optimizer::execute,
                                                "debug", "selfmonitor", "verbose", "off"))))));

        // The profile argument inspects, it does not switch. Switching still means editing
        // config.yml and reloading; the reply says so. Offering the names without wiring them
        // to anything would be a suggestion that leads nowhere, and wiring them to a live
        // profile change would alter scheduling from a tab-completed keystroke.
        root.then(Commands.literal("profile")
                .executes(ctx -> dispatch(ctx, optimizer::execute, "profile"))
                .then(Commands.argument("profile", StringArgumentType.word())
                        .suggests(PROFILE_SUGGESTIONS)
                        .executes(ctx -> dispatch(ctx, optimizer::execute, "profile",
                                StringArgumentType.getString(ctx, "profile")))));

        return root.then(unknownSubcommand(optimizer::execute)).build();
    }

    /**
     * Catch-all for input that matches no literal. Registers no suggestion provider, so it
     * contributes nothing to the completion menu and only ever runs on execution.
     */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String>
    unknownSubcommand(Handler handler) {
        return Commands.argument("subcommand", StringArgumentType.word())
                .executes(ctx -> dispatch(ctx, handler,
                        StringArgumentType.getString(ctx, "subcommand")));
    }

    private static Predicate<CommandSourceStack> permission(String node) {
        return source -> source.getSender().hasPermission(node);
    }

    private static int dispatch(CommandContext<CommandSourceStack> ctx, Handler handler, String... args) {
        handler.run(ctx.getSource().getSender(), args);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    /** The shape every ServerTune command handler already had. */
    @FunctionalInterface
    private interface Handler {
        boolean run(CommandSender sender, String[] args);
    }
}
