# Sync vs async initializers

The library offers two initializer types. This page explains what each one guarantees and how to choose
between them.

## At a glance

| | `StartupSyncInitializer<T>` | `StartupAsyncInitializer<T>` |
|---|---|---|
| `create` | `fun create(context: Context): T` | `suspend fun create(context: Context): T` |
| Runs on | The thread that requested initialization | The resolved dispatcher (`withContext`) |
| Provider-driven startup | Main thread, blocking | Launched on the library's coroutine scope, not awaited |
| Blocks the requester | Yes, until `create()` returns | No |
| Timeout | None | None (use `awaitAllStartJobs(timeoutMs)` to bound the wait) |
| Dependencies | `dependencies()` (sync classes) | `dependencies()` (async classes) and `syncDependencies()` (sync classes) |
| Extra options | `retainAfterStartup()` | `dispatcher()`, `priority()`, `retainAfterStartup()` |
| Failure | `StartupExtensionException`; fatal during provider startup | Isolated in its job; surfaces from `awaitAllStartJobs()` |

Both are declared in the manifest as `<meta-data>` of `InitializationProvider`
([Getting started](../getting-started.md#register-the-initializers-in-the-manifest)).

## Sync initializers

Use a sync initializer when the rest of the app must be able to rely on the component immediately after
startup, and when initialization is cheap: for example a logger, a crash reporter, or a configuration holder.

```kotlin
class ConfigInitializer : StartupSyncInitializer<Config> {
    override fun create(context: Context): Config = Config.load(context)
}
```

Threading in 3.0:

- During provider-driven startup, Android calls `InitializationProvider.onCreate()` on the main thread,
  so manifest-declared sync initializers run there.
- On-demand calls (`initializeComponent`, `doInitialize`) run `create()` on the calling thread. This is a
  behavior change from 2.x, where the call was dispatched to main. See the
  [3.0 migration guide](../migration-3.0.md).
- Sync dependencies of an async initializer (`syncDependencies()`) run in the coroutine context that is
  resolving them.

There is no timeout. A slow `create()` on the provider path blocks the main thread and risks an ANR.
With `strictModeCheckEnabled = true`, each sync `create()` runs under a `StrictMode.ThreadPolicy`
(`detectAll().penaltyLog()`) so accidental disk or network access shows up in logcat.

Coordination is per component: unrelated sync initializers can run concurrently on different threads,
and concurrent requests for the same component share one result.

## Async initializers

Use an async initializer for I/O, disk, network or CPU-heavy work that the first frame does not need.

```kotlin
class DatabaseInitializer : StartupAsyncInitializer<Database> {
    override fun dispatcher() = Dispatchers.IO

    override suspend fun create(context: Context): Database =
        Database.open(context)
}
```

Facts to keep in mind:

- `create()` runs inside `withContext` of the resolved dispatcher. The precedence is the non-null
  `dispatcher()` value, then `AppStartupConfig.defaultAsyncDispatcher`, then `Dispatchers.Default`.
- A dispatcher can be `Main`, `Unconfined` or custom, so async does not guarantee a different thread.
- One failing async initializer does not cancel its siblings (`SupervisorJob`). The failure surfaces when
  startup is awaited. If nobody awaits, nothing crashes and nothing is reported.
- The scheduling of the launch can be influenced with `priority()`
  ([Priorities and deferred startup](priorities-and-deferred.md)) and capped with
  `maxConcurrentAsyncInitializers` ([Configuration](configuration.md)).

## Retaining results

By default the result of `create()` is stored in an internal map for the life of the process, and later
requests for the same class return it. Override `retainAfterStartup()` to return `false` for components that
exist only for their side effects:

```kotlin
class MigrationInitializer : StartupSyncInitializer<Unit> {
    override fun create(context: Context) {
        DatabaseMigrationRunner(context).runPendingMigrations()
    }
    override fun retainAfterStartup(): Boolean = false
}
```

A transient component that is requested again later (for example with `initializeComponent`) runs `create()`
again, so make sure it is safe to call more than once.

## Choosing

| Situation | Use |
|---|---|
| Needed synchronously right after process start, and cheap | Sync |
| Touches disk, network or does heavy CPU work | Async with an appropriate `dispatcher()` |
| Needs a specific thread confinement | Async with a dedicated dispatcher |
| Must run only after the first frame | Async with `StartupPriority.DEFERRED` |
| Needs main-thread-only APIs | Request it from main, or async with `Dispatchers.Main.immediate` |
| Side effects only, result not needed later | Either, with `retainAfterStartup() = false` |

## Next steps

- [Dependencies](dependencies.md)
- [Priorities and deferred startup](priorities-and-deferred.md)
- [Troubleshooting](troubleshooting.md)
- [API reference: initializers](../api-reference.md#2-initializers)
