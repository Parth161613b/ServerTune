package com.servertune.pluginscan;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockExpEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Installs and removes the timing wrappers. The only class that mutates other plugins'
 * registrations, and it does so for the length of one window and no longer.
 *
 * <h2>Why only these event types</h2>
 *
 * <p>Wrapping every {@link HandlerList} on the server would mean touching several hundred lists
 * and adding a timer to registrations that fire once an hour, which buys nothing and multiplies
 * the risk of leaving something wrapped. The list below is the set of events that actually fire
 * at high frequency on a busy server - physics and redstone, entity ticking and targeting,
 * hopper transfers, chunk streaming, player movement. A plugin that is expensive on the main
 * thread is overwhelmingly likely to be expensive in one of these.
 *
 * <p>The cost of that choice is stated in the report rather than hidden: an event type outside
 * this set was never timed, so a plugin's absence from the results is not a clean bill of health.
 * See {@link PluginScanReport#unmeasuredNote()}.
 *
 * <p>Classes that inherit their handler list from a parent are covered by wrapping the parent.
 * {@code BlockBreakEvent} shares {@link BlockExpEvent}'s list; {@code CreatureSpawnEvent} and
 * {@code ProjectileLaunchEvent} share {@link EntitySpawnEvent}'s. The lists are de-duplicated by
 * identity, so naming both a parent and a child would wrap the shared list once, not twice.
 *
 * <h2>Restoration</h2>
 *
 * <p>Teardown does not call {@code unregisterAll} or re-register from scratch. It unregisters
 * exactly the wrapper it installed and registers back the exact original object, so a plugin's
 * registration is the same instance after the scan as before it. Order within a priority is
 * re-baked by Paper on registration and does not affect dispatch, which is ordered by
 * {@code EventPriority}, and every wrapper reports the same priority as the listener it replaced.
 *
 * <p>Restoration is driven from a recorded list of installed wrappers rather than by re-scanning
 * the handler lists, so a plugin that registered or unregistered its own listeners mid-window
 * cannot cause ServerTune to restore something twice or miss one. A wrapper whose list no longer
 * contains it is simply not found, and unregister is a no-op.
 *
 * <h2>Listener lifecycle mid-window</h2>
 *
 * <p>A wrapper reports its original's {@code getListener()} and {@code getPlugin()} (they are
 * passed to {@code super}), because the wrapper must behave identically to what it replaced. The
 * consequence is that Paper's own removal paths match the wrapper: if a plugin unregisters one of
 * its listeners during the window, or the plugin is disabled (which unregisters by plugin), the
 * wrapper is removed along with the original.
 *
 * <p>Restore therefore treats removal as authoritative. Before it re-registers an original, it
 * checks that the wrapper is still installed on the list. If the wrapper is gone, the plugin (or
 * the disable path) already removed the original, and re-registering it would resurrect a listener
 * the plugin had deliberately dropped. The listener's registration must be a no-op in that case,
 * not a second copy. A listener registered <em>during</em> the window is never touched: it was
 * never wrapped, so it is not in the install list, and nothing in restore runs over it.
 */
final class PluginScanProfiler {

    /** The high-frequency lists this profiler is willing to touch. */
    private static final List<HandlerList> HOT_EVENT_HANDLERS = List.of(
            BlockPhysicsEvent.getHandlerList(),
            BlockRedstoneEvent.getHandlerList(),
            BlockFromToEvent.getHandlerList(),
            BlockGrowEvent.getHandlerList(),
            BlockPlaceEvent.getHandlerList(),
            BlockExpEvent.getHandlerList(),
            BlockPistonExtendEvent.getHandlerList(),
            BlockPistonRetractEvent.getHandlerList(),
            EntitySpawnEvent.getHandlerList(),
            EntityDamageEvent.getHandlerList(),
            EntityDeathEvent.getHandlerList(),
            EntityTargetEvent.getHandlerList(),
            EntityPickupItemEvent.getHandlerList(),
            EntityMoveEvent.getHandlerList(),
            EntityPathfindEvent.getHandlerList(),
            ItemMergeEvent.getHandlerList(),
            ProjectileHitEvent.getHandlerList(),
            InventoryClickEvent.getHandlerList(),
            InventoryDragEvent.getHandlerList(),
            InventoryOpenEvent.getHandlerList(),
            InventoryMoveItemEvent.getHandlerList(),
            ChunkLoadEvent.getHandlerList(),
            ChunkUnloadEvent.getHandlerList(),
            PlayerMoveEvent.getHandlerList(),
            PlayerInteractEvent.getHandlerList(),
            PlayerItemHeldEvent.getHandlerList(),
            PlayerToggleSneakEvent.getHandlerList(),
            PlayerStatisticIncrementEvent.getHandlerList());

    /** What was installed where, so teardown restores precisely and nothing else. */
    private final List<Installed> installed = new ArrayList<>();

    private final Plugin self;

    private boolean active;

    PluginScanProfiler(Plugin self) {
        this.self = self;
    }

    /** The number of distinct handler lists this profiler considers, for the report. */
    static int instrumentedEventCount() {
        return distinctLists().size();
    }

    /**
     * Wraps every foreign registration on the hot lists. Must run on the main thread: it mutates
     * handler lists, and Paper's own registration path is not designed for concurrent callers.
     *
     * @return the number of registrations wrapped
     */
    int install(PluginScanSession session) {
        if (active) {
            return 0;
        }
        active = true;

        int wrapped = 0;
        for (HandlerList list : distinctLists()) {
            // Snapshot first: register/unregister mutate the list we are iterating.
            RegisteredListener[] current = list.getRegisteredListeners();
            for (RegisteredListener listener : current) {
                if (!shouldWrap(listener)) {
                    continue;
                }
                PluginActivity activity = session.activityFor(listener.getPlugin().getName());
                TimedListener timed = new TimedListener(listener, activity);
                list.unregister(listener);
                list.register(timed);
                installed.add(new Installed(list, timed));
                wrapped++;
            }
        }
        return wrapped;
    }

    /**
     * Puts every original registration back, except where the plugin itself removed it.
     *
     * <p>Paper's {@code unregister(Listener)} and {@code unregister(Plugin)} match a wrapper,
     * because a wrapper deliberately reports the listener and plugin it replaced. So a plugin that
     * unregisters its own listener mid-window, or that is disabled mid-window, has already had its
     * registration removed - the wrapper went with it. Re-registering the original in that case
     * would put back a listener the plugin had dropped and leave the server dispatching to a
     * listener nobody owns. Restore checks for the wrapper before it restores, and skips the
     * originals whose wrappers are gone.
     *
     * <p>Safe to call twice: the second call sees {@code active == false} and returns.
     */
    void restore() {
        if (!active) {
            return;
        }
        active = false;

        Map<HandlerList, Set<RegisteredListener>> present = snapshotInstalledLists();
        int dropped = 0;

        for (Installed entry : installed) {
            try {
                Set<RegisteredListener> onList = present.get(entry.list);
                if (onList == null || !onList.contains(entry.wrapper)) {
                    // The owning plugin removed this registration during the window. Its removal
                    // is authoritative; putting the original back would resurrect it.
                    dropped++;
                    continue;
                }
                entry.list.unregister(entry.wrapper);
                entry.list.register(entry.wrapper.original());
            } catch (RuntimeException e) {
                // One list failing must not strand the remaining hundreds still wrapped.
                self.getLogger().warning("ServerTune could not restore a wrapped listener for "
                        + entry.wrapper.getPlugin().getName() + ": " + e.getMessage());
            }
        }

        if (dropped > 0) {
            self.getLogger().info("ServerTune left " + dropped + " listener(s) unregistered on "
                    + "teardown because their plugin removed them during the diagnostic.");
        }
        installed.clear();
    }

    /**
     * One snapshot per handler list, taken before anything is restored, so the presence check
     * costs a bounded number of array reads rather than one scan per wrapper.
     */
    private Map<HandlerList, Set<RegisteredListener>> snapshotInstalledLists() {
        Map<HandlerList, Set<RegisteredListener>> present = new IdentityHashMap<>();
        for (Installed entry : installed) {
            if (present.containsKey(entry.list)) {
                continue;
            }
            Set<RegisteredListener> identity =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            try {
                Collections.addAll(identity, entry.list.getRegisteredListeners());
            } catch (RuntimeException e) {
                // Unreadable list: fall back to attempting the restore rather than dropping it.
                identity.add(entry.wrapper);
            }
            present.put(entry.list, identity);
        }
        return present;
    }

    boolean isActive() {
        return active;
    }

    /** Visible for the service's cleanup assertions. */
    int installedCount() {
        return installed.size();
    }

    /**
     * ServerTune does not time itself. Wrapping our own listeners would report ServerTune as a
     * performance source in its own diagnostic, which is noise, and the wrapper's cost is
     * measured separately as overhead. A listener already wrapped is skipped so a second install
     * can never nest timers.
     */
    private boolean shouldWrap(RegisteredListener listener) {
        if (listener instanceof TimedListener) {
            return false;
        }
        Plugin owner = listener.getPlugin();
        return owner != null && owner != self;
    }

    /**
     * The hot lists with duplicates removed by identity. Several event classes legitimately share
     * one {@code HandlerList} through inheritance, and wrapping a shared list twice would
     * double-count every call through it.
     */
    private static List<HandlerList> distinctLists() {
        Map<HandlerList, Boolean> seen = new IdentityHashMap<>();
        List<HandlerList> out = new ArrayList<>(HOT_EVENT_HANDLERS.size());
        for (HandlerList list : HOT_EVENT_HANDLERS) {
            if (seen.putIfAbsent(list, Boolean.TRUE) == null) {
                out.add(list);
            }
        }
        return out;
    }

    private record Installed(HandlerList list, TimedListener wrapper) {
    }
}
