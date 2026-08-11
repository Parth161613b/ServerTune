# Redstone: what the Paper API allows

Why the redstone module limits pistons and dispensers but only *monitors* redstone signals
and observers. Every signature below was read out of the paper-api jar on this project's
compile classpath (`io.papermc.paper:paper-api:26.2.build.111-stable`, see
`build.gradle.kts`) with `javap`. Nothing here is from memory.

## What is cancellable, and what is not

| Event | Cancellable? | Verified signature |
|---|---|---|
| `BlockRedstoneEvent` | **NO** | extends `BlockEvent` only. Methods: `getOldCurrent()`, `getNewCurrent()`, `setNewCurrent(int)` |
| `BlockPistonExtendEvent` | **YES** | extends `BlockPistonEvent implements Cancellable` |
| `BlockPistonRetractEvent` | **YES** | same base class |
| `BlockDispenseEvent` | **YES** | `implements Cancellable` |
| `BlockPreDispenseEvent` (Paper) | **YES** | `implements Cancellable` |
| `BlockPhysicsEvent` | **YES** | `implements Cancellable` |

## Redstone update limiting is not implemented

`BlockRedstoneEvent` does **not** implement `Cancellable`. There is no `setCancelled` to
call. The only mutator is `setNewCurrent(int)`, which changes the signal *strength* a
component reports — it does not prevent the update from propagating, and abusing it to force
0 would silently rewrite redstone logic rather than limit its cost.

Searching the whole jar for redstone-limiting surface returns only block *data* types
(`RedstoneWire`, `Observer`, `Powerable`, `AnaloguePowerable`) — state accessors, not
update-rate controls.

`ServerTickManager` exists but is the `/tick` command's freeze/step/sprint control.
`setTickRate`/`setFrozen` affect the **entire server**, not redstone. Using it to throttle
redstone would freeze mobs, plants, and the day cycle too.

Therefore **`max-updates-per-tick` is not implemented and does not appear in the config**,
rather than shipping as a setting that does nothing. Implementing it would require NMS.

### Why no NMS

Intercepting redstone updates means hooking the level's neighbour-update path, which is the
single hottest code path in the tick loop and differs across Mojang mappings per patch. A
reflective, version-specific hook there is a crash and corruption risk far exceeding the
benefit, and it would silently disable itself on the next Paper update — the worst failure
mode for an optimization plugin. Piston and dispenser limiting via the cancellable events
above addresses most real redstone lag without touching internals.

## Pistons — limiting implemented

`BlockPistonEvent` is cancellable, so per-second, per-chunk and cooldown limits are genuinely
enforceable. Cancelling a piston is a real gameplay change (it breaks flying machines and
technical farms), which is why it is off by default and gated behind explicit configuration.

## Observers — monitoring only

There is no observer event in the API. Observers fire when the block in front changes state.
What is observable is the resulting neighbour update via `BlockPhysicsEvent`, whose
`getSourceBlock()` lets an update be attributed to an observer. That gives an activity signal.

`BlockPhysicsEvent` *is* cancellable, but cancelling it does not "limit an observer" — it
suppresses arbitrary block physics, which detaches torches, floats gravel and desyncs clients.
So observers are monitored and reported, never limited. `BlockPhysicsEvent` monitoring is
itself off by default because that event is extremely high frequency; see the performance note
below.

## Dispensers and droppers — limiting implemented

`BlockPreDispenseEvent` (Paper) and `BlockDispenseEvent` (Bukkit) are both cancellable.
Paper's fires earlier, before the item is selected, so it is the cheaper interception point
and the one used for rate limiting.

## Performance note on BlockPhysicsEvent

`BlockPhysicsEvent` fires for essentially every block update on the server. A handler on it is
itself a potential lag source. It is therefore opt-in
(`observer-monitoring.enabled: false`), its handler does nothing but a `Material` switch and
an atomic increment on a cached per-chunk counter, and it samples rather than recording every
event when `sample-rate` > 1.
