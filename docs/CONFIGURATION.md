# ServerTune configuration reference

Every key in `config.yml`, what it does, and what it costs. File location:
`plugins/ServerTune/config.yml`, written on first start.

Apply changes with `/optimizer reload`. That re-reads the file and pushes it into every live
component: alert thresholds, self-monitoring budgets, the scheduler, the diagnostics engine,
module settings, and finally the performance guard. The guard reloads last on purpose — the
steps before it turn on everything `config.yml` asks for without knowing the server is
struggling, so reloading the guard afterwards lets it re-assert the current state over that.

A reload cannot change: `api-version`, the command and permission registrations, or the module
set. Those are fixed at load.

---

## `general`

| Key | Default | Effect |
|---|---|---|
| `debug` | `false` | Per-cycle detail from each module to the console. Leave off in production — it logs on every optimization cycle. |

There is no `general.enabled` — see "Known limitations" in the README.

---

## `performance`

| Key | Default | Effect |
|---|---|---|
| `profile` | `balanced` | One of `light`, `balanced`, `aggressive`, `custom`. An unrecognized value falls back to `balanced`. |

The profile sets **only** the health and deep-analysis intervals. It does not enable or
disable a module, and it does not change module intervals.

| Profile | health | deep-analysis |
|---|---|---|
| `light` | 200 t (10 s) | 2400 t (120 s) |
| `balanced` | 100 t (5 s) | 1200 t (60 s) |
| `aggressive` | 60 t (3 s) | 600 t (30 s) |
| `custom` | reads `performance.intervals.health` | reads `performance.intervals.deep-analysis` |

### `performance.intervals`

Ticks; 20 ticks = 1 second.

| Key | Default | Effect |
|---|---|---|
| `health` | `100` | Health sample interval. Only read when `profile: custom`. |
| `deep-analysis` | `1200` | Hotspot ranking and diagnostics sampling. Only read when `profile: custom`. |

### `performance.intervals.modules`

These apply **whatever the profile is**.

| Key | Default | Used by |
|---|---|---|
| `item-merge` | `200` | item **and** XP modules |
| `entity-cleanup` | `400` | entity **and** projectile modules |
| `chunk-unload` | `600` | chunk module |
| `hopper` | `400` | hopper module |
| `redstone` | `400` | redstone module |

Entity and chunk *tracking* are absent from this list on purpose: both are event-driven
(spawn/remove, load/unload), so they have no polling interval to tune.

---

## `alerts`

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | Master switch for all alerting. |
| `console` | `true` | Also log alerts to the console. |
| `cooldown-seconds` | `60` | Minimum gap between alerts, per alert type. |

In-game alerts go to players holding `serverhealth.alert`.

### `alerts.tps`

| Key | Default |
|---|---|
| `enabled` | `true` |
| `warning.threshold` / `warning.sustained-seconds` | `18.0` / `10` |
| `critical.threshold` / `critical.sustained-seconds` | `15.0` / `5` |
| `emergency.threshold` / `emergency.sustained-seconds` | `10.0` / `3` |

### `alerts.mspt`

| Key | Default |
|---|---|
| `enabled` | `true` |
| `warning.threshold` / `warning.sustained-seconds` | `40.0` / `10` |
| `critical.threshold` / `critical.sustained-seconds` | `50.0` / `5` |

**These are alerting thresholds only.** 50 ms is exactly 20 TPS, so they are set to fire
early and often. The performance guard and diagnostics use their own, higher MSPT values
(55 / 70, under `performance-guard.states`) and do not read these. The discrepancy is
deliberate: alerts are meant to tell you early, the guard is meant to act only once the
server is genuinely degraded. Do not "fix" it by aligning the two.

### `alerts.recovery`

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | Track recovery from alert conditions. |
| `notify-on-recovery` | `true` | Announce when the server recovers. |

---

## `fallback`

These keys predate the performance guard and the guard still reads them, so an existing
config keeps working. `performance-guard.states.fallback.tps` overrides `trigger.tps`.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | Turns the **whole guard** off when false, state machine included. Equivalent to `performance-guard.enabled: false`, which takes precedence if both are set. |
| `trigger.tps` | `10.0` | TPS at which FALLBACK is entered. |
| `trigger.sustained-seconds` | `3` | How long it must hold. |
| `recovery.tps` | `18.0` | TPS required to begin recovering. |
| `recovery.sustained-seconds` | `10` | How long it must hold. |

Entering RECOVERING also requires MSPT back under the guard's warning value, so a server
holding 18 TPS with 90 ms spikes is not called recovered.

### `fallback.recovery-stages`

Seconds from the moment recovery begins. Nothing heavy resumes early.

| Key | Default | Resumes |
|---|---|---|
| `stage-2-delay` | `5` | Health monitoring |
| `stage-3-delay` | `10` | Entity tracking |
| `stage-4-delay` | `15` | Chunk tracking |
| `stage-5-delay` | `20` | Optimization modules |
| `stage-6-delay` | `30` | Deep analysis |

Recovery is complete when stage 6 finishes. There is no separate cooldown-after-recovery
setting: `stage-6-delay` already *is* the wait before full operation resumes, and a second
timer on top of it would be a number that changed nothing.

Until stage 6 completes, the state stays RECOVERING. The only automatic way out of
RECOVERING is a relapse back to FALLBACK.

---

## `self-monitoring`

The plugin measuring itself.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | Time each subsystem. `/optimizer debug` reads this. |
| `budget.health-monitor` | `5.0` | Milliseconds per execution. |
| `budget.deep-analysis` | `10.0` | Milliseconds per execution. |
| `budget.optimization-module` | `5.0` | Default budget for every module without its own entry. |
| `warn-on-budget-exceeded` | `true` | Log when a subsystem runs over budget. |
| `suspend-on-budget-exceeded` | `true` | Suspend a module that consistently exceeds it. |
| `violations-before-suspend` | `3` | Consecutive violations required. |

A module suspended for budget is **never auto-resumed**. It waits for `/optimizer reload` or
the guard's staged recovery. That is deliberate: a module that just proved it is expensive
should not quietly restart itself.

---

## `server-health`

Command access is controlled by the `serverhealth.*` permissions, not by a config switch.

| Key | Default | Effect |
|---|---|---|
| `live.enabled` | `true` | Allow `/serverhealth live`. |
| `live.update-interval` | `40` | Ticks between refreshes (2 s). |
| `live.max-duration` | `300` | Seconds before a live view expires itself. `0` = unlimited. |

There is no cache-duration setting: the snapshot cache holds whatever the last health sample
produced, and every command prints that snapshot's real age. There is no expiry to configure.
Sample more often with `performance.intervals.health`.

---

## `optimization.modules`

Module switches are read exactly as written and are **not** changed by `performance.profile`.

Every module supports `disabled-worlds: []` — a list of world names the module skips
entirely.

### `item` — default **enabled**

Gameplay impact: low to medium. Performance impact: medium.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | |
| `merging.enabled` | `true` | Merge nearby item stacks. |
| `merging.radius` | `3.0` | Blocks. |
| `lifetime-ticks` | `6000` | 5 minutes — the vanilla default, so this changes nothing until you lower it. |
| `max-per-chunk` | `100` | Past this, the **oldest** items in the chunk are removed. |
| `global-limit.enabled` | `false` | Server-wide item cap. |
| `global-limit.max` | `5000` | |
| `protected-types` | `DIAMOND`, `NETHERITE_INGOT`, `ELYTRA` | Never merged or removed. |

Named items are never touched, regardless of type.

### `xp` — default **enabled**

Gameplay impact: low. Performance impact: low to medium.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | |
| `merging.enabled` | `true` | Merge nearby orbs. |
| `merging.radius` | `3.0` | Blocks. |
| `max-per-chunk` | `50` | |

### `projectile` — default **enabled**

Gameplay impact: low. Performance impact: low.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | |
| `arrow-lifetime-ticks` | `1200` | 60 s. |
| `trident-lifetime-ticks` | `1200` | 60 s. |
| `other-lifetime-ticks` | `600` | 30 s. |

### `entity` — default **disabled**

Gameplay impact: **high** — this limits mob spawns. Performance impact: high.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `false` | |
| `limits.hostile-global` | `-1` | `-1` disables the limit. |
| `limits.passive-global` | `-1` | |
| `limits.water-global` | `-1` | |
| `limits.villager-global` | `-1` | |
| `per-chunk.enabled` | `true` | Only read when the module is enabled. |
| `per-chunk.hostile` | `20` | |
| `per-chunk.passive` | `15` | |
| `per-chunk.water` | `10` | |
| `protected-types` | `ENDER_DRAGON`, `WITHER` | Never removed. |

### `chunk` — default **disabled**

Gameplay impact: medium — may unload chunks players expect to stay loaded. Performance
impact: high, in the sense that it reclaims chunk memory.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `false` | |
| `inactive-unloading.enabled` | `true` | |
| `inactive-unloading.timeout-seconds` | `300` | Inactivity before a chunk is a candidate. |
| `inactive-unloading.max-per-cycle` | `10` | Bounded so one cycle cannot spike the tick. |
| `protect-spawn-chunks` | `true` | |
| `spawn-chunk-radius` | `10` | Chunks around spawn to protect. |
| `protected-chunks` | `[]` | Explicit list, format `"world:x:z"`. |

Chunks with players in them are never unloaded regardless of these settings.

### `mob` — **NOT IMPLEMENTED**

| Key | Default | Effect |
|---|---|---|
| `enabled` | `false` | **Inert.** Setting it to true does nothing. |

No mob AI module ships in 1.0.0. The key is present because it is the one such a module
would use. See "Known limitations" in the README.

### `hopper` — default **disabled**

Gameplay impact: **high** when throttling or limits are on. Performance impact: medium.

**Read this before enabling.** Vanilla's 8-tick transfer interval and the hopper's per-tick
container search are server internals — changeable only in `spigot.yml`
(`ticks-per.hopper-transfer`, `ticks-per.hopper-check`) or via NMS, never through the Bukkit
API. This module cannot make hoppers tick less often. What it *can* do is cancel the transfer
and pickup work through cancellable events, which removes the item movement and container
churn even though the block entity still ticks. Real, but partial.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `false` | |
| `transfer-throttle.enabled` | `false` | Cancels transfers happening too soon after the last. |
| `transfer-throttle.min-ticks-between-transfers` | `8` | Vanilla transfers every 8 ticks, so **values at or below 8 will rarely cancel anything**. Raise above 8 to actually slow hoppers. Slows item farms and sorting systems. |
| `transfer-throttle.throttle-item-pickups` | `false` | Also throttle hoppers picking up dropped items — often the bigger cost. Slows collection farms. |
| `per-chunk-limit.enabled` | `false` | Enforced when a hopper is **placed**. Never removes existing hoppers. |
| `per-chunk-limit.max-hoppers` | `64` | |
| `network-limit.enabled` | `false` | Caps connected-hopper chains, checked once per placement. |
| `network-limit.max-connected-hoppers` | `64` | |
| `network-limit.scan-cap` | `256` | Hard cap on blocks visited per placement check, so placing next to a huge network cannot become a long scan. |
| `hotspot-detection.enabled` | `true` | **Monitoring only**, never changes gameplay. |
| `hotspot-detection.hoppers-per-chunk` | `24` | Flag a chunk holding at least this many. |
| `hotspot-detection.transfers-per-window` | `500` | Flag a chunk with at least this many observed transfers. |
| `hotspot-detection.log-to-console` | `false` | |
| `inactive-reporting.threshold-seconds` | `300` | **Monitoring only** — an idle hopper fires no events, so there is nothing to throttle. |

### `redstone` — default **disabled**

Gameplay impact: none in `vanilla`/`conservative`, **high** in `balanced`/`aggressive`.
Performance impact: medium.

**API limitation.** `BlockRedstoneEvent` is not cancellable — it exposes `setNewCurrent(int)`
and no `setCancelled`. There is no safe way to cap redstone updates per tick through the API,
so `max-updates-per-tick` is deliberately **not provided** rather than faked. See
`REDSTONE-API-NOTES.md`. Piston and dispenser events *are* cancellable, so those
limits are real enforcement.

| Key | Default |
|---|---|
| `enabled` | `false` |
| `mode` | `conservative` |

**Modes:**

| Mode | Behaviour |
|---|---|
| `vanilla` | Module inactive, no listeners registered, zero overhead. |
| `conservative` | Monitoring and hotspot detection only. Nothing cancelled. **Default.** |
| `balanced` | Limits only extreme activity (40 piston moves/chunk/sec). Very large piston arrays and rapid dispenser farms may be throttled; normal doors, lamps and small farms are not. |
| `aggressive` | Hard limits (10 piston moves/chunk/sec, 2 tick cooldown). **WILL BREAK technical farms** — flying machines stall, fast clocks are cut, TNT duplicators and rapid item farms stop working. This is intentional; enable deliberately. |
| `custom` | Every value read from the `custom` section. |

#### `redstone.custom`

Values here **override the selected mode**, so `balanced` plus one override is a valid setup
— you do not need `custom` mode to change one number.

| Key | Default | Effect |
|---|---|---|
| `piston.limit-enabled` | `false` | Real enforcement. Cancelling a piston breaks flying machines and fast farms. |
| `piston.max-per-chunk-per-second` | `40` | |
| `piston.max-per-chunk-per-window` | `120` | Reported only; does not cancel on its own. |
| `piston.cooldown-ticks` | `0` | Enforced quiet after a trip. Only used by `cancel-and-cooldown`. |
| `piston.action-on-limit` | `monitor` | `monitor`, `cancel`, or `cancel-and-cooldown`. |
| `dispenser.limit-enabled` | `false` | Real enforcement. Covers droppers too. |
| `dispenser.max-per-chunk-per-second` | `30` | |
| `dispenser.action-on-limit` | `monitor` | |
| `observer.monitoring-enabled` | `false` | **Monitoring only** — observers are never limited. Off by default because `BlockPhysicsEvent` fires for nearly every block update, so a handler on it is itself a potential lag source. |
| `observer.sample-rate` | `8` | Record 1 event in N. Higher = cheaper and less precise. |
| `redstone-change-monitoring` | `true` | Count signal changes per chunk. Monitoring only — the event cannot be cancelled. |

#### `redstone.hotspot-detection`

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | Monitoring only. |
| `activity-per-window` | `600` | Flag a chunk with at least this much total activity. |
| `log-to-console` | `false` | |
| `max-reported` | `10` | |
| `idle-threshold-seconds` | `300` | Drop tracking for chunks quiet this long. (Sits at the module level, not under `hotspot-detection`.) |

---

## `performance-guard`

Owns the state machine: `NORMAL → WARNING → CRITICAL → FALLBACK → RECOVERING`. Both TPS and
MSPT are considered — MSPT moves first when a server starts to struggle, because TPS is a
lagging average, so a bad MSPT escalates the state just like a bad TPS does.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | Takes precedence over `fallback.enabled` if both are set. |

### `performance-guard.states`

Each state needs its metric sustained for `sustained-seconds` before the state actually
changes, so a single bad sample never moves anything. TPS values fall back to the matching
`alerts.*` / `fallback.*` keys if omitted.

| State | `tps` | `mspt` | `sustained-seconds` |
|---|---|---|---|
| `warning` | `18.0` | `55.0` | `10` |
| `critical` | `15.0` | `70.0` | `5` |
| `fallback` | `10.0` | — | `3` |
| `recovery` | `18.0` | — | `10` |

The MSPT numbers look high next to `alerts.mspt.*` on purpose. 50 ms *is* one tick, so MSPT
50 is exactly 20 TPS; using the alert thresholds for state changes would put a healthy server
in CRITICAL permanently. These mirror the TPS ladder: 1000/18 = 55.6, 1000/15 = 66.7.

### `performance-guard.stability`

What stops the guard flapping when TPS sits on a threshold. Raise these if you still see
state churn; lower them for faster reaction.

| Key | Default | Effect |
|---|---|---|
| `tps-hysteresis` | `1.0` | Improving out of a state requires clearing the threshold by this margin. |
| `mspt-hysteresis` | `5.0` | Same, for MSPT. |
| `de-escalation-sustained-seconds` | `10` | De-escalating always takes this long, whichever state you are leaving. |
| `min-state-seconds` | `5` | No two state changes closer than this. **Escalation into FALLBACK ignores it** — protecting a dying server must not wait on an anti-flap timer. |

### `performance-guard.actions`

| Key | Default | Effect |
|---|---|---|
| `alerts` | `true` | Notify staff about state changes. Independent of `automatic-optimization`. |
| `automatic-optimization` | **`false`** | Allow the guard to enable/suspend modules on your behalf. |
| `alert-permission` | `serverhealth.alert` | Permission required to receive in-game notifications. |
| `log-to-console` | `true` | |

**With the defaults, the guard reports and changes nothing.** The per-state
`enable-modules` / `suspend-modules` lists are only read when `automatic-optimization` is
true.

Each of `normal`, `warning`, `critical`, `fallback`, `recovering` takes:

| Key | Default | Effect |
|---|---|---|
| `notify-staff` | `false` for `normal`, `true` for the rest | |
| `enable-modules` | `[]` | Module names, e.g. `item-optimization`, `chunk-optimization`. |
| `suspend-modules` | `[]` | |

Valid module names: `item-optimization`, `xp-optimization`, `projectile-optimization`,
`entity-optimization`, `chunk-optimization`, `hopper-optimization`, `redstone-optimization`.

### `performance-guard.fallback-actions`

What happens on entering FALLBACK, beyond suspending the optimizer's own modules. These
apply **even with `automatic-optimization` false** — this is the plugin getting out of the
way, not an optimization it is performing on your server.

| Key | Default | Effect |
|---|---|---|
| `cancel-expensive-tasks` | `true` | Cancel the optimizer's scheduled module tasks. The health monitor keeps running so recovery can still be detected. |
| `stop-deep-analysis` | `true` | Stop hotspot ranking and diagnostic sweeps until recovery completes. |

Commands, health monitoring, alerts and recovery detection keep running in FALLBACK.

### `performance-guard.adaptive-monitoring`

Scales the health-monitor interval to the current state. **Only the cheap health sample
changes frequency** — it reads counters Paper already maintains. Deep analysis and chunk
scanning are never sped up by this, so a bad state cannot create a feedback loop.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | |
| `multipliers.normal` | `1.0` | |
| `multipliers.warning` | `0.75` | Slightly more often. |
| `multipliers.critical` | `0.5` | More frequent, still only the cheap sample. |
| `multipliers.fallback` | `1.5` | Back off — just enough to notice recovery. |
| `multipliers.recovering` | `1.0` | |

Resulting intervals are clamped to a floor and a ceiling, so no multiplier can stop sampling
entirely or make it run every tick.

---

## `diagnostics`

Backs `/serverhealth diagnose`, `/serverhealth hotspots` and `/optimizer diagnose`.

**Commands never scan.** One periodic sample runs on the main thread, stores an immutable
snapshot, and every command reads that snapshot and prints its age.

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | |
| `async` | `true` | Analyse the stored snapshot off the main thread. Safe because the analyzer and recommendation engine are pure — they hold no Bukkit reference. Only the sampler is main-thread work, and this setting does not affect it. |

### `diagnostics.sampling`

| Key | Default | Effect |
|---|---|---|
| `interval-ticks` | `1200` | How often one sample is taken. Defaults to `performance.intervals.deep-analysis`. |
| `chunk-scan-budget` | `2000` | Upper bound on chunks visited by one per-chunk entity pass. `Chunk#getEntities()` is main-thread-only, so the pass is bounded and resumes where it stopped — successive cycles still cover everything. |
| `stale-after-seconds` | `30` | Past this age, a report highlights its "Data sampled X seconds ago" line in yellow. The report is still shown; stale data is labelled, never hidden. |

### `diagnostics.thresholds`

Every one of these is a **measurement** threshold. Crossing one means the number was above
the line — not that it caused a tick to run long.

| Group | Key | Default |
|---|---|---|
| `tps` | `warning` / `critical` / `severe` | `18.0` / `15.0` / `10.0` |
| `mspt` | `warning` / `critical` | `55.0` / `70.0` |
| `cpu` | `warning` / `critical` | `80.0` / `95.0` |
| `memory` | `warning-percent` / `critical-percent` | `75.0` / `90.0` |
| `chunks` | `loaded-warning` / `loaded-critical` | `3000` / `5000` |
| `chunks` | `load-rate-warning` / `load-rate-critical` | `50` / `150` per second |
| `chunks` | `inactive-warning` | `500` |
| `entities` | `total-warning` / `total-critical` | `3000` / `8000` |
| `entities` | `items-warning` / `items-critical` | `500` / `2000` |
| `entities` | `xp-orbs-warning` | `300` |
| `entities` | `mobs-warning` | `2000` |
| `entities` | `villagers-warning` | `200` |
| `block-entities` | `hoppers-warning` | `2000` |
| `block-entities` | `total-warning` | `8000` |

TPS and MSPT inherit the performance-guard ladder so a diagnosis and a guard state never
disagree about the same server. They deliberately do **not** inherit `alerts.mspt.*`.

### `diagnostics.hotspots`

Per-chunk thresholds for the "Potential hotspots" section. A chunk is listed when a count is
above its line here. That is a correlation with nothing else attached: the plugin counts
objects and events, it does not profile the tick loop, so it cannot and does not claim a
listed chunk is causing lag.

| Key | Default | Inherits from |
|---|---|---|
| `entities-per-chunk` | `50` | — |
| `hoppers-per-chunk` | `24` | hopper module's own hotspot value |
| `block-entities-per-chunk` | `100` | — |
| `hopper-transfers-per-window` | `500` | hopper module's own value |
| `redstone-activity-per-window` | `600` | redstone module's own value |
| `max-reported` | `5` | How many hotspots one report lists, worst-scoring first. |

---

## `recommendations`

| Key | Default | Effect |
|---|---|---|
| `enabled` | `true` | Backs `/optimizer recommendations`. |

Suggestions only. Nothing in this path writes `config.yml`, enables a module or schedules a
task — the engine holds no config handle at all, so it could not apply a change if asked to.
Every suggestion names the key to edit and what turning it on will do to gameplay. Making the
edit and reloading is yours.

Runs off-thread under `diagnostics.async`, from the same snapshot as a diagnosis.

---

## Invalid values

`config.yml` is validated on load and on every reload. An unusable value is logged as a
warning naming the key, the value that was rejected and the replacement, and the built-in
default is used for that key. One bad key never stops the plugin from loading and never
discards the rest of your configuration.

**Corrections are in-memory only.** `config.yml` on disk is never rewritten, so your file
keeps your mistake and your comments — the logged warning tells you where to look. Fix it and
`/optimizer reload`.

Validation covers:

- `performance.profile` — an unrecognized name falls back to `balanced`
- every interval under `performance.intervals` — must be positive; zero or negative would make
  the scheduler run the module every tick, the opposite of the point
- TPS thresholds (`alerts.tps.*`, `fallback.trigger.tps`, `fallback.recovery.tps`) — must be
  within 0–20. Above 20 could never trigger
- MSPT thresholds (`alerts.mspt.*`) — must be positive, unbounded above, because a badly
  stalled server really can sit at 2000 ms
- every `sustained-seconds` and `alerts.cooldown-seconds` — must be positive

Keys outside that list are read with a built-in default when absent or wrong-typed, but are
not range-checked. If a value you set does not appear to take effect, check the console at
startup for a warning naming it.

