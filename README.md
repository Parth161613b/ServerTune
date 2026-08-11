# ServerTune

ServerTune is a highly configurable Paper plugin for server performance monitoring, optimization, diagnostics and fallback protection.

It samples TPS, MSPT, CPU, memory, chunks and entities on a fixed interval. On top of that sit optional optimization modules, an on-demand plugin profiler, and a state machine that pulls the plugin's own work out of the way when the server is already struggling.

## Features

- TPS, MSPT, CPU, memory, chunk, entity, player, plugin and datapack monitoring
- Configurable alerts on warning/critical/emergency thresholds
- Seven independently toggleable optimization modules
- Diagnostics engine with per-chunk hotspot ranking and severity-graded findings
- Config recommendations derived from measured samples (never auto-applied)
- On-demand plugin performance diagnostics
- Adaptive performance guard with fallback and staged recovery
- Self-monitoring: per-subsystem budgets, with over-budget modules suspended

## Commands

Three trees. `/serverhealth` (`sh`, `health`) reads, `/optimizer` (`opt`, `optimize`, `servertune`, `st`) controls, `/serverhealthcore` (`shc`, `healthcore`) is a one-screen summary. Bare `/serverhealth` and `/optimizer` run the health overview and `status` respectively.

| Command | Description | Permission |
|---|---|---|
| `/serverhealth` | TPS, MSPT, CPU, memory, players, chunks, entities, status | `serverhealth.use` |
| `/serverhealth version` | Plugin, server and Bukkit versions | `serverhealth.use` |
| `/serverhealth chunks` | Loaded chunk totals, per-world counts, load/unload rates | `serverhealth.use` |
| `/serverhealth entities` | Entity totals and the top types | `serverhealth.use` |
| `/serverhealth plugins` | Installed plugins and their enabled state | `serverhealth.use` |
| `/serverhealth datapacks` | Enabled and disabled datapacks | `serverhealth.use` |
| `/serverhealth diagnose` | Findings by severity from the last sample, with the sample's age | `serverhealth.diagnose` |
| `/serverhealth hotspots` | Highest-scoring chunks from the last sample | `serverhealth.diagnose` |
| `/serverhealth live` / `live stop` | Auto-refreshing view, 2 s updates, 5 min cap | `serverhealth.use` |
| `/serverhealth debug` | Collector timings and internal state | `serverhealth.admin` |
| `/serverhealthcore` | Compact core metrics | `serverhealth.core` |
| `/optimizer status` | Profile, intervals, module count, current performance, feature switches | `optimizer.use` |
| `/optimizer version` | Plugin, author, API and server version | `optimizer.use` |
| `/optimizer chunks` / `entities` | Totals plus the relevant modules' states | `optimizer.use` |
| `/optimizer profile [name]` | Shows what intervals each profile resolves to. Inspects only — switching means editing config.yml | `optimizer.use` |
| `/optimizer reload` | Re-reads config.yml and re-applies it to every live component | `optimizer.reload` |
| `/optimizer recommendations` | Suggested config changes from the last sample | `optimizer.recommendations` |
| `/optimizer diagnose` | Same diagnosis as `/serverhealth diagnose` | `optimizer.diagnose` |
| `/optimizer diagnose plugins [3-60]` | Starts a temporary plugin profiling window (default 10 s) | `optimizer.diagnose.plugins` |
| `/optimizer diagnose plugins cancel` | Stops a running window and reports what it observed | `optimizer.diagnose.plugins` |
| `/optimizer debug` | Per-subsystem execution timings | `optimizer.admin` |
| `/optimizer debug selfmonitor [on\|off\|status]` | Toggles SelfMonitor console logging at runtime; does not touch config.yml | `optimizer.admin` |
| `/optimizer debug selfmonitor verbose [on\|off]` | Verbose SelfMonitor logging; bare form toggles | `optimizer.admin` |


## Permissions

All default to `op`; `servertune.*` grants everything.

| Permission | Default | Purpose |
|---|---|---|
| `serverhealth.use` | op | Read-only health views |
| `serverhealth.core` | op | `/serverhealthcore` |
| `serverhealth.diagnose` | op | Reads an existing sample: `diagnose`, `hotspots` |
| `serverhealth.admin` | op | `/serverhealth debug` |
| `serverhealth.alert` | op | Receives alerts and guard state changes (not a command node) |
| `optimizer.use` | op | Read-only optimizer views |
| `optimizer.reload` | op | Reload configuration |
| `optimizer.recommendations` | op | View recommendations (read-only; changes nothing) |
| `optimizer.diagnose` | op | Reads the periodic sample |
| `optimizer.diagnose.plugins` | op | **Starts a measurement.** Deliberately separate from `optimizer.diagnose` — every other diagnose subcommand reads a sample that already exists, while this one temporarily wraps other plugins' event handlers. Granting the cheap read should not grant the measurement. |
| `optimizer.admin` | op | Debug output and SelfMonitor runtime controls |

## Compatibility

| Platform | Support                                 |
|---|-----------------------------------------|
| Paper | **26.2**                                |
| Java | **25 or newer** (hard requirement)      |
| Spigot / Bukkit | **Not supported.** Uses only Paper APIs |


## Configuration


| Section | Controls |
|---|---|
| `performance.*` | Sampling profile and the health/deep-analysis intervals |
| `alerts.*` | Warning, critical and emergency thresholds, cooldowns, recovery notices |
| `optimization.modules.*` | Per-module enable switches and their tuning. Never changed by `performance.profile` |
| `fallback.*` | Trigger and recovery thresholds, staged recovery delays |
| `performance-guard.*` | The state machine, hysteresis, and whether it may act rather than only report |
| `self-monitoring.*` | Per-subsystem millisecond budgets and logging |
| `diagnostics.*` | Sampling budget, staleness, hotspot thresholds |
| `diagnostics.plugin-scan.*` | Default and maximum window, result count, minimum confidence |
| `recommendations.*` | Recommendation output |

### Optimization modules

**Entity / item** — `item` and `xp` merge nearby stacks and orbs with per-chunk caps; `projectile` removes arrows and tridents after some intervals ; `entity` applies global and per-chunk caps by category. The first three are on by default, `entity` is off.

**Redstone / block systems** — `hopper` throttles transfers and pickups with per chunk and connected network limits; `redstone` rate-limits pistons and dispensers across five modes from `vanilla` to `aggressive`. Both off by default.

**Chunks** — `chunk` unloads chunks inactive past a timeout, bounded per cycle, with spawn chunk and explicit protection lists. Off by default.


## Performance & Safety

- ### **Self-monitoring.** 

- ### **Fallback.** 

- ### **Recommendations**

### Known limitations

- **No `general.enabled`.** A plugin-level "enabled: false" that still loaded the jar, registered the commands and ran the scheduler would be a setting that does nothing, so it is not offered. Stop the plugin the way you stop any other, by removing the jar.
- **No `mob` module in 1.0.0.** The `optimization.modules.mob.enabled` key exists in `config.yml` but is inert — setting it to `true` does nothing. The key is reserved for a future module.
- **No `max-updates-per-tick` for redstone.** `BlockRedstoneEvent` is not cancellable, and enforcing a limit properly requires NMS hooks into the neighbour-update path, which is the hottest code in the tick loop and breaks on every Paper update. Piston and dispenser limits *are* enforced through cancellable events. See `docs/REDSTONE-API-NOTES.md`.

## Links

- **Discord** - <https://discord.gg/UHccj8G67b>

Bug reports should include your Paper and Java versions, `/optimizer status` output, which modules are enabled, and the full stack trace if there is one. For performance complaints include `/optimizer debug` the fastest way to tell whether the cost is ServerTune's.

For Support Contact **Discord** - <https://discord.gg/UHccj8G67b>

