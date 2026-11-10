# Troubleshooting

Symptoms, causes and fixes, based on behavior verified in the library sources.

## ANR or slow cold start

**Cause.** Sync initializers declared in the manifest run on the main thread during
`ContentProvider.onCreate()` and have no timeout. Heavy work there blocks the main thread.
`getProviderInfo()` is also a blocking IPC call made once at startup (trace `Startup::getProviderInfo`).

**Fix.**

- Move I/O and CPU-heavy work to a `StartupAsyncInitializer` with a suitable `dispatcher()`.
- Enable `strictModeCheckEnabled` in debug builds to surface disk and network access in `create()`.
- Look at the `Startup::create::<FQN>` slices in a system trace ([Observability](../observability.md)).

## StrictMode concurrency warning does not appear

The warning about too many concurrently active async initializers goes through the debug logger. It needs
**both** `strictModeCheckEnabled = true` and `debugLoggingEnabled = true`, and the active count must exceed
`strictModeConcurrencyThreshold` (default: the number of available processors). It is logged at most once
per startup.

## `startupState` stays `IN_PROGRESS` (or `IDLE`)

- `COMPLETED` and `ERROR` are set only when `awaitAllStartJobs` runs (directly or through
  `onAppStartupLaunched`). Jobs finishing on their own do not change the state, so make sure something
  awaits startup.
- A startup whose async initializers are all `DEFERRED` stays `IDLE`: deferred jobs are tracked separately
  and never move the state.
- A cancelled await, including a timeout, does not change the state.
- `ERROR` is sticky; a later successful await does not set `COMPLETED`.

## A failing async initializer is not noticed

An async failure is isolated in its job and surfaces only when startup is awaited. If nobody calls
`awaitAllStartJobs()`, nothing crashes and nothing is reported. A failing `DEFERRED` initializer is never
awaited at all, so observe `metricsFlow` (`success = false`, `errorType`, `errorMessage`) to see it.

## `StartupExtensionException` at launch

| Message or cause | Meaning |
|---|---|
| Wraps `ClassNotFoundException` | A `<meta-data>` name does not match a class. Check the fully-qualified name and R8 rules. |
| `expected a concrete class` | The manifest points to an abstract class or an interface. |
| Wraps `NameNotFoundException` | The `InitializationProvider` entry is missing from the merged manifest. |
| Wraps an exception from `create()` | A sync initializer threw. During provider startup this is fatal for the process. |

A class that is listed with the wrong kind (for example an async class declared as `sync-initializer`) is
silently ignored rather than reported.

## Dependency cycle errors

| Message | When |
|---|---|
| `Cycle detected in sync initializers: [A, B]` | `SyncOrderingStrategy.Topological`, before any `create()` |
| `Cycle detected in async initializers: [A, B]` | Both async strategies, before any coroutine launches |
| `Cannot initialize <FQN>. Cycle detected.` | Detected at runtime along a dependency chain (for example with `Lazy` sync ordering) |
| `Cannot initialize <FQN>. Cross-thread cycle detected.` | Two threads each own a sync initializer and wait for the other's |

Use `SyncOrderingStrategy.Topological` in debug builds to get the complete list early. Details in
[Dependencies](dependencies.md).

## Deadlock between main and a worker

The library detects cycles among waits that go through its own sync lock coordinator. It cannot detect a
wait that goes through something else, for example a worker holding a sync initializer while it waits for
the main thread, and main requesting that same initializer. Avoid blocking a worker on main while main may
request the same initializer. See the [3.0 migration guide](../migration-3.0.md#main-thread-only-initialization).

## An initializer's `create()` ran again

Components with `retainAfterStartup() == false` are not kept, so each new request re-runs `create()`.

## Deferred work never starts

`DEFERRED` initializers wait for the first-frame signal or `deferredStartupTimeoutMs` (default 5 s). In a
process that never draws a frame, only the timeout releases them. With a custom `FirstFrameSignal`, make
sure it completes. See [Priorities and deferred startup](priorities-and-deferred.md).

A `Log.w` message about "priority inversion" means a `DEFERRED` initializer is needed by an eager one and
was promoted to eager; this is not an error.

## Settings seem ignored

`maxConcurrentAsyncInitializers` and `metricsReplayCapacity` are read when the singleton is constructed.
Call `configure` in `Application.attachBaseContext`, before the provider runs. See
[Configuration](configuration.md#when-settings-take-effect).

## Metrics missing or lost

`metricsFlow` replays the last `metricsReplayCapacity` (default 256) metrics to late collectors, and a live
collector that lags by more than that loses the oldest ones. The drop warning is logged only when
`debugLoggingEnabled` is `true`.

## Memory leaks

`metricsFlow` is a hot flow on a process-lifetime singleton. A collector that captures an `Activity` keeps
it alive while the collection runs, so use a lifecycle-aware scope. A custom `FirstFrameSignal` is held for
the whole process and must not retain an `Activity` or `Fragment`.

## Next steps

- [Observability](../observability.md)
- [Architecture: error handling](../architecture.md#10-error-handling)
- [Documentation index](../README.md)
