## Parallel World Ticking Design

### Scope

This document defines the first-stage `parallel-world` architecture for the Akarin / Paper 1.12.2 fork.

The goal is world-level parallelism only:

- Different `WorldServer` instances may tick in parallel.
- Each individual world remains single-threaded internally.
- Entity AI, tile entities, block ticks, chunk state, weather, and other mutable world state stay bound to that world's owner thread.

Out of scope for this stage:

- Per-entity or per-chunk parallel ticking inside the same world
- Async mob or animal AI
- Async Bukkit event dispatch by default
- Async chunk population / plugin populator execution

### Owner Thread Model

When `akarin.parallel-world.enabled` is `true`, every `WorldServer` is assigned a dedicated owner thread and mailbox executor.

Rules:

- Only the owner thread may directly mutate that world's state.
- The coordinator thread may schedule work for a world, but must not directly mutate world-owned structures.
- If code running on world A needs to modify world B, it must enqueue work onto world B's mailbox.

Core helpers:

- `isWorldThread(WorldServer)`
- `ensureWorldThread(WorldServer)`
- `execute(WorldServer, Runnable)`
- `callSync(WorldServer, Callable<T>)`
- `shutdown()`

Owner thread names should be stable and human-readable:

- `WorldThread-overworld`
- `WorldThread-the_nether`
- `WorldThread-the_end`
- `WorldThread-<dimensionId>`

### Tick Barrier

`MinecraftServer` becomes the tick coordinator when parallel-world is enabled.

Per server tick:

1. Main/coordinator thread runs pre-world global tasks that must remain serialized.
2. Coordinator submits one world tick task to each world's owner thread.
3. Each owner thread runs that world's normal tick sequence serially.
4. Coordinator waits for all world tasks to finish.
5. If any world tick fails, the exception is rethrown on the coordinator and server crash semantics stay unchanged.
6. Only after the barrier completes does the coordinator proceed with global post-world work such as network flush, scheduler heartbeat, watchdog bookkeeping, and similar tasks.

Constraints:

- The next server tick must not begin until the prior world's tick barrier has completed.
- Global TPS remains 20 TPS.
- No world gets a second overlapping tick.

### Cross-World Queue

Cross-world modifications must not directly mutate another world's data.

Required pattern:

- Detect source thread / target world mismatch.
- Enqueue onto the target world's mailbox.
- If the caller needs a result, use `callSync`.

Deadlock rule:

- Avoid blocking world A while world B is waiting on world A.
- Prefer coordinator-mediated handoff or one-way enqueue plus state transition.

For player and entity world transfer, the long-term target model is:

1. Freeze / detach from source world
2. Enqueue target-world attach work
3. Load target chunk on target owner thread
4. Add entity/player to target world
5. Send dimension / position packet on the coordinator-safe path
6. Cleanup remaining source-world state

The initial implementation may keep legacy transfer logic in place where a full state machine is too invasive, but any new safety wrappers must avoid introducing half-async races.

### Bukkit Compatibility Policy

Default compatibility policy is conservative.

- Bukkit scheduler sync tasks continue to run on the main coordinator thread.
- Bukkit events are not made globally async.
- `Bukkit.isPrimaryThread()` continues to mean the main coordinator thread only.
- World owner threads are not reported as primary threads.

Rationale:

- Reporting all world threads as primary would hide plugin thread-safety bugs.
- Keeping the old primary-thread contract reduces silent corruption risk.
- New internal helpers such as `isWorldThread` / `ensureWorldThread` should be used for NMS/CraftBukkit paths that are intentionally world-thread-bound.

### Configuration

Feature flag:

- `akarin.parallel-world.enabled=false` by default

Debug options:

- `akarin.parallel-world.debug-log`
- `akarin.parallel-world.task-timeout`

If config wiring is incomplete in some early step, a system property fallback is acceptable:

- `-Dakarin.parallelWorld=true`

### Watchdog / Debugging

The watchdog output should expose:

- Whether parallel-world is enabled
- Coordinator thread state
- Each world thread name and owning world
- Which world is currently ticking
- World task timeout or rejected execution events

This makes stalls attributable to a specific world thread instead of only the main coordinator thread.

### Known Limitations

Initial versions of this architecture are expected to retain risk around:

- Player dimension change and portal transfers
- Cross-world teleports initiated from Bukkit API paths
- Main-thread plugin code calling world APIs while a world is ticking on its owner thread
- Global systems with historical assumptions that all world state lives on the main thread

Those paths should be tightened incrementally with explicit queue handoff instead of broad synchronization or opportunistic concurrent collections.
