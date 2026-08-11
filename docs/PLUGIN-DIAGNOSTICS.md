# On-demand plugin performance diagnostics

`/optimizer diagnose plugins` — what it measures, what it cannot measure, and why.

This document states what the feature does and does not measure. If you only read one
section, read [What cannot be measured](#what-cannot-be-measured-reliably).

---

## 1. Measurement strategy

ServerTune wraps other plugins' **event handler registrations** for the length of one window and
times each call.

For each of a fixed set of high-frequency event types, the profiler takes the event's
`HandlerList`, and for every `RegisteredListener` on it that belongs to another plugin, it:

1. unregisters the original registration,
2. registers a subclass of `RegisteredListener` that holds the original and delegates to it,
3. records `System.nanoTime()` either side of the delegated call.

The wrapper is constructed from the original's own listener, executor, priority, owning plugin
and ignore-cancelled flag — all readable through public getters and all accepted by the public
constructor — so `super.callEvent(event)` runs exactly the code the server would have run.

At teardown the exact original object is registered back. Restoration is driven from a recorded
list of what was installed, not from re-scanning the handler lists, so a plugin that registered
or unregistered its own listeners mid-window cannot cause a double restore or a missed one.

**Removal during the window wins.** A wrapper reports the listener and plugin it replaced, which is
what makes it behave identically — and which also means Paper's own `unregister(Listener)` and
`unregister(Plugin)` match the wrapper. So a plugin that unregisters a listener mid-window, or that
is disabled mid-window, has already had that registration removed. Teardown checks that the wrapper
is still on the list before restoring, and skips the ones that are gone; re-registering there would
resurrect a listener the plugin deliberately dropped. A listener registered *during* the window was
never wrapped, is not in the install list, and teardown never touches it. When teardown skips any
listener for this reason it logs the count.

Baseline TPS/MSPT/players/chunks/entities come from ServerTune's existing `SnapshotCache`. No
second health monitor exists.

**Why event handlers and not something else.** Scheduled task bodies are the other obvious place
plugin cost hides, and Paper's public scheduler API exposes no execution timing at all (see §3).
Event handlers are the only place where the public API allows an exact, non-reflective timer
around another plugin's code.

## 2. Paper APIs used

Every signature below was verified with `javap` against the paper-api jar on this project's
compile classpath. Nothing is from memory and nothing is reflective.

| API | Used for |
|---|---|
| `HandlerList.getRegisteredListeners()` | Enumerating registrations on a hot event type |
| `HandlerList.unregister(RegisteredListener)` | Removing the original |
| `HandlerList.register(RegisteredListener)` | Installing the wrapper, and restoring the original |
| `RegisteredListener` (non-final, public `callEvent`) | Subclassed as the timing wrapper |
| `RegisteredListener.getListener/getExecutor/getPriority/getPlugin/isIgnoringCancelled` | Reconstructing the registration faithfully |
| `<Event>.getHandlerList()` | Static, per event type — no class lookup by name |
| `Bukkit.isPrimaryThread()` | Splitting sync from async cost |
| `Bukkit.getPluginManager().getPlugins()` | Inventory, collected once at session start |
| `Plugin.getName/isEnabled/getPluginMeta` | Inventory |
| `BukkitScheduler` (via the project's `OptimizerScheduler`) | The one-second ticker |
| `System.nanoTime()` | The timer itself |

**Not used, deliberately:**

- **Timings** (`TimedRegisteredListener`, `PluginManager.useTimings()`) — deprecated across the
  board and no longer populated by Paper. It is a dead path; reading it would report zeros.
- **Reflection** — the public API is sufficient, so there is no reason to reach past it.
- **NMS** — same.
- **Spark** — a third-party plugin, not an API this plugin may depend on.

## 3. What cannot be measured reliably

**Per-plugin CPU or MSPT attribution is not possible, and this feature does not claim it.**

- **Scheduled task execution time is not exposed.** `BukkitTask` has exactly five methods:
  `getTaskId`, `getOwner`, `isSync`, `isCancelled`, `cancel`. There is no duration, no call count
  and no start time. A plugin whose cost lives in a repeating task will therefore **not appear in
  a report**, and the report says so every time.
- **Command handlers, chunk generators, pathfinders and world-gen hooks are not timed.**
- **Only the listed hot event types are wrapped.** Everything else is untimed.
- **Total CPU per plugin is not obtainable.** The JVM does not attribute CPU by classloader, and
  Paper exposes nothing per-plugin.
- **Correlation is not causation, and a ten-second window is short.** A plugin that shows the most
  handler time may simply be the one doing the most useful work.

Consequently the report **never** says "Plugin X used 4.73 ms of CPU". It says how many timed
calls were observed, what they totalled, and what share of *measured handler time* that was —
never what share of the tick, because the unwrapped part of the tick was not measured.

**A plugin absent from a report has not been cleared. It was not measured.** Every report prints
that sentence.

## 4. Confidence levels

| Level | What earns it |
|---|---|
| `HIGH` | Direct timing of the plugin's own handlers, over at least 20 calls |
| `MEDIUM` | Direct timing, but a thin sample — the average may not be stable |
| `LOW` | No direct timing. The plugin owns scheduled tasks, which is inventory, not evidence |

Impact and confidence are independent axes: impact is "how big", confidence is "how sure".
A plugin can be HIGH impact with MEDIUM confidence (large cost, few samples).

Impact bands are shares of the window's **measured handler time**, with an absolute floor of
1.0 ms so that a plugin holding 100% of a trivial total is still reported as negligible.

## 5. Ranking

Ranked on **measured main-thread handler time**, descending. Then peak, then name.

- **Task count is never scored.** A plugin with 100 idle tasks is cheaper than one task that
  walks every entity in the world. Task ownership produces at most a LOW-confidence note, and
  such a finding always sorts below any plugin that was actually measured.
- **Async time is never scored.** It is reported and labelled separately, because async work does
  not delay the tick. Scoring it would rank a plugin doing background I/O above one stalling the
  server.
- **Plugin name is never an input.** No heuristics, no known-bad list.
- **Elevated MSPT never creates a finding.** It sharpens the wording for a plugin that was
  *already* measurably expensive, and the sentence it adds explicitly says correlation is not
  proof of cause.

The only action ServerTune will recommend is a controlled A/B test in staging. It will never
recommend disabling a production plugin.

## 6. Duration and limits

| | Value |
|---|---|
| Default window | 10 s (`diagnostics.plugin-scan.default-duration-seconds`) |
| Configured maximum | 30 s (`diagnostics.plugin-scan.max-duration-seconds`) |
| **Compiled hard ceiling** | **60 s — config.yml cannot raise it** |
| Minimum window | 3 s |
| Progress line | every 5 s, never per tick |

The ceiling is enforced in two places: `ConfigValidator` corrects and logs an out-of-range value
at load, and `PluginScanSettings`' constructor clamps again. The second gate holds even for a
config that never reached the validator, which is why no value on disk can produce an unbounded
session. `max-duration-seconds: 86400` yields 60.

Expiry is checked by the one-second ticker against elapsed wall time, not trusted to a delayed
task, so a lagging or rescheduled tick cannot extend the window.

## 7. Overhead

Per wrapped handler call the profiler does: two `nanoTime()` reads, one `isPrimaryThread()`
check, one atomic add, one atomic increment, and a CAS only when a new maximum is observed.
Nothing is allocated, nothing is logged, nothing is looked up in a map — the accumulator
reference is resolved once at wrap time and captured in a field.

**Measured off-server** (`PluginScanOverheadBenchmarkTest`, on one development machine,
Java 25 — run it on yours for numbers that apply to your hardware):

```
Plugin scan wrapper (2x nanoTime + record): 2,000,000 calls in 99.8 ms = 49.9 ns/call
  At 5,000 wrapped handler calls per tick that is 0.250 ms of the 50 ms tick budget.
Plugin scan accumulator under 4-way contention: 800,000 records in 26.3 ms = 32.8 ns/record
Plugin scan report for 40 measured plugins: 4.385 ms (105 lines)
```

**These figures are a floor, not the whole cost.** They exclude `Bukkit.isPrimaryThread()`, which
cannot be called from a unit test, and they exclude whatever the extra virtual dispatch costs in
a real dispatch path. They also describe one machine, not yours.

### Measuring the real overhead on your server

1. Idle the server at a known state (fixed player count, no one moving between runs).
2. Record `/serverhealth` MSPT over 60 s with no diagnostic running. That is your baseline.
3. Run `/optimizer diagnose plugins 30`.
4. Compare the report's **Average MSPT** and **Peak MSPT** against the baseline.
5. The report also prints ServerTune's own accumulated profiling cost when it exceeds 1 ms
   total, measured at runtime by the plugin itself.
6. Repeat three times and take the median. A single run on a live server is noise.

If step 4 shows a material MSPT increase on your hardware and plugin set, shorten the window or
leave `enabled: false`. The feature is not worth the distortion it would introduce.

## 8. Cleanup

One exit path, `finish()`, in this order: stop profiling → build report → release → send.

| Resource | Released by |
|---|---|
| Ticker task | `OptimizerScheduler.cancelTask("plugin-scan-ticker")` |
| Wrapped registrations | Each original re-registered from the recorded install list, except where the owning plugin removed it during the window |
| Per-plugin accumulators | `PluginScanSession.clear()` |
| Requester reference | Nulled after the report is sent |
| Session object | Nulled |

Triggered by all four endings: window expiry, `cancel`, fallback abort, and plugin disable.
`PluginLifecycle` calls `shutdown()` **first** in `onDisable`, before anything else, so no other
plugin is left holding a dead ServerTune class on a live handler list.

The state machine is one-way and synchronized: a cancel racing the timeout produces exactly one
terminal transition, so the profiler cannot be torn down twice or report twice.

### Verifying dormancy

`/serverhealth debug` prints the diagnostic's state. With the command never run it reads:

```
Plugin scan: dormant (no session, no listeners wrapped, no task)
```

Anything else while no session is running is a leak. Run a diagnostic, let it finish, and check
that line again — it must return to `dormant`.

## 9. Fallback safety

The ticker checks `PerformanceGuard.isInFallback()` every second. On a critical state the session
aborts immediately, everything is restored, and the report leads with:

```
Plugin diagnostic aborted because server performance became critical.
```

A start attempt while already in fallback is refused outright: profiling a collapsing server
makes both problems worse, and the measurement would be dominated by the emergency rather than
by any plugin.

## 10. Commands, permissions, configuration

```
/optimizer diagnose plugins            start, using the configured default window
/optimizer diagnose plugins <seconds>  start with an explicit window (clamped, and says so)
/optimizer diagnose plugins cancel     stop and report what was observed
```

Aliases: `/servertune`, `/opt`, `/optimize`, `/st`.

Permission: **`optimizer.diagnose.plugins`**, default `op`. It is deliberately *not* a child of
`optimizer.diagnose` in effect — every other `diagnose` subcommand reads a sample that already
exists and costs nothing, while this one temporarily wraps other plugins' handlers. Granting the
cheap read should not grant the measurement. Brigadier filters the completion menu by permission,
so an unauthorized player is never shown `plugins` under `diagnose`.

```yaml
diagnostics:
  plugin-scan:
    enabled: true
    default-duration-seconds: 10
    max-duration-seconds: 30      # hard ceiling of 60 is compiled, not configurable
    top-results: 10
    minimum-confidence: LOW
    progress-interval-seconds: 5
```

## 11. Tests

80 automated tests across six classes, all Bukkit-free:

| Class | Covers |
|---|---|
| `PluginScanSettingsTest` | Config validation, duration clamping, the hard ceiling, confidence parsing |
| `PluginActivityTest` | Aggregation, sync/async separation, peak tracking, atomicity under contention |
| `PluginScanRankerTest` | Ranking, impact and confidence classification, and every claim the ranker refuses to make |
| `PluginScanSessionTest` | Lifecycle, duplicate prevention, cancellation, timeout, cleanup, repeated runs |
| `PluginScanFormatterTest` | Report generation, the no-findings wording, the never-claim-causation wording |
| `PluginScanOverheadBenchmarkTest` | The overhead figures above |

Full suite: **269 tests, 0 failures.**

No test pretends to measure Paper runtime behaviour. paper-api is `compileOnly` and is not on the
test classpath, so the Bukkit-touching classes — `TimedListener`, `PluginScanProfiler`,
`PluginDiagnosticsService`, `PluginScanConfigLoader` — are verified by compilation and by the
manual tests below, not by mocks that would only assert what the mock was told to return.

## 12. Verifying it on a live server

These checks need a running Paper server, so they are not part of the automated suite. Run
them against a test server if you want to confirm the feature behaves as documented on your
setup.

1. **Dormancy.** Start the server, never run the command, run `/serverhealth debug`. Expect
   `Plugin scan: dormant`. Confirm no `plugin-scan-ticker` task exists.
2. **Wrap and restore.** With at least one other plugin installed, run a 10 s diagnostic. Confirm
   the start log line reports a non-zero wrapped count, and that `/serverhealth debug` returns to
   `dormant` afterwards. Confirm the other plugin still works.
3. **Repeated runs.** start → finish → start → finish → start → cancel → start → finish.
   After each, `/serverhealth debug` must read `dormant` and the wrapped count must be the same
   each time it starts, with no growth.
4. **Duplicate prevention.** Start a diagnostic, then start another. Expect
   `Plugin diagnostic is already running.`
5. **Cancellation.** Start a 30 s window, cancel at ~5 s. Expect a partial report labelled
   cancelled, and a return to `dormant`.
6. **Timeout.** Start a 10 s window and leave it. It must end on its own at 10 s.
7. **Duration clamp.** Set `max-duration-seconds: 86400`, reload, run `diagnose plugins 86400`.
   Expect the validator warning at load and an actual window of no more than 60 s.
8. **Fallback abort.** Start a diagnostic and drive the server into fallback. Expect the abort
   message and full restoration.
9. **Permission filtering.** As a player with `optimizer.diagnose` but not
   `optimizer.diagnose.plugins`, type `/optimizer diagnose ` and confirm `plugins` is not offered
   and cannot be run.
10. **Disable mid-window.** Start a diagnostic, then `/stop` the server. Confirm a clean shutdown
    with no listener-related errors from other plugins.
11. **Plugin unloaded mid-window.** If you have a plugin manager, disable another plugin during a
    window. Confirm the diagnostic still finishes and restores cleanly, and that the disabled
    plugin's listeners are **not** present on any handler list afterwards.
12. **Listener mutation mid-window.** With a test plugin that can register and unregister a
    listener on command: (a) register a new listener during a window and confirm it is still
    registered exactly once after teardown; (b) unregister an existing listener during a window and
    confirm it is **not** back after teardown, and that the teardown log line reports the skip.
13. **Overhead.** The procedure in §7.
