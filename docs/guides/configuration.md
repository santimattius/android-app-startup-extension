# Configuration

All runtime options live in `AppStartupConfig`, configured with `AppStartupInitializer.configure { }`.
This page lists every option, its default and when to change it.

## Applying a configuration

```kotlin
class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AppStartupInitializer.configure {
            debugLoggingEnabled = BuildConfig.DEBUG
            strictModeCheckEnabled = BuildConfig.DEBUG
            syncOrderingStrategy = SyncOrderingStrategy.Topological
        }
    }
}
```

`configure` builds a new immutable `AppStartupConfig` from the block, replaces the library-wide
configuration, and enables or disables debug logging according to `debugLoggingEnabled`. Options you do not
set return to their defaults on each call; `configure` does not merge with a previous configuration.

### When settings take effect

Call `configure` before `InitializationProvider` runs; the earliest safe point is
`Application.attachBaseContext`.

Two options are read **once, when the `AppStartupInitializer` singleton is constructed**:

- `maxConcurrentAsyncInitializers`
- `metricsReplayCapacity`

The singleton is created the first time `AppStartupInitializer.getInstance` is called, which is during
`InitializationProvider.onCreate()` at the latest. A later `configure` call does not change these two options
for the running instance. All other options are read on every use, so a later call wins for them.

## Options

| Option | Type | Default | When to change it |
|---|---|---|---|
| `debugLoggingEnabled` | `Boolean` | `false` | Turn on in debug builds to see discovery and initialization messages (tag `StartupLogger`). |
| `strictModeCheckEnabled` | `Boolean` | `false` | Turn on in debug builds to detect I/O inside sync `create()`; also enables the concurrency warning (below). |
| `syncOrderingStrategy` | `SyncOrderingStrategy` | `Lazy` | Use `Topological` to detect sync cycles up front. |
| `asyncInitializerStrategy` | `AsyncInitializerStrategy` | `Concurrent` | Use `Validated` to launch only roots and reduce the coroutine count for chained initializers. |
| `maxConcurrentAsyncInitializers` | `Int?` | `null` (unbounded) | Set a cap when many async initializers saturate the thread pool. Values `<= 0` become `null`. Read at singleton construction. |
| `defaultAsyncDispatcher` | `CoroutineDispatcher` | `Dispatchers.Default` | Use `Dispatchers.IO` or a custom dispatcher when most initializers are I/O-bound. |
| `strictModeConcurrencyThreshold` | `Int` | `Runtime.getRuntime().availableProcessors()` | Adjust the active-count level that triggers the concurrency warning. |
| `firstFrameSignal` | `FirstFrameSignal?` | `null` | Provide a custom signal for `DEFERRED` initializers or a fake in tests. |
| `deferredStartupTimeoutMs` | `Long` | `5_000L` | Bound how long `DEFERRED` work waits for the first frame. `<= 0` flushes immediately. |
| `metricsReplayCapacity` | `Int` | `256` | Raise if more than 256 metrics can be emitted. Values `<= 0` fall back to 256. Read at singleton construction. |
| `dispatcherInstrumentationEnabled` | `Boolean` | `false` | Debug and measurement only; do not enable in production. |

A full example:

```kotlin
AppStartupInitializer.configure {
    debugLoggingEnabled = BuildConfig.DEBUG
    strictModeCheckEnabled = BuildConfig.DEBUG
    syncOrderingStrategy = SyncOrderingStrategy.Topological
    asyncInitializerStrategy = AsyncInitializerStrategy.Validated
    maxConcurrentAsyncInitializers = 4
    defaultAsyncDispatcher = Dispatchers.Default
    deferredStartupTimeoutMs = 5_000L
    metricsReplayCapacity = 256
    dispatcherInstrumentationEnabled = BuildConfig.DEBUG
}
```

## Details by option

### `strictModeCheckEnabled` and `debugLoggingEnabled`

With `strictModeCheckEnabled`, each sync `create()` runs under a thread `StrictMode` policy
(`detectAll().penaltyLog()`); the previous policy is restored afterwards, so it does not affect the app's own
StrictMode setup. Violations appear in logcat.

The same flag enables the async concurrency warning: if the number of concurrently active async `create()`
bodies exceeds `strictModeConcurrencyThreshold`, one warning per startup is emitted. That warning goes
through the debug logger, so it appears **only when both `strictModeCheckEnabled` and `debugLoggingEnabled`
are `true`**. The `Startup::marker::strictModeConcurrency` trace marker is emitted once, at the same point, and does not
depend on `debugLoggingEnabled`.

### `maxConcurrentAsyncInitializers`

Launching every async initializer at once on a shared pool can flood it (a "coroutine storm"). The cap
creates a `Semaphore`; a permit is acquired after the initializer's dependencies have been resolved and
released when `create()` finishes. A parent therefore never holds a permit while waiting for a child, so a
cap of `1` cannot deadlock on dependency chains. Measure first with `StartupMetric.queueDelayMs`,
`permitWaitMs` and `concurrentActiveCount` ([Observability](../observability.md)). Background on the
problem: [Coroutine storm](../coroutine-storm.md).

### `defaultAsyncDispatcher`

Used for async initializers whose `dispatcher()` returns `null`. Precedence: per-instance `dispatcher()`,
then this option, then `Dispatchers.Default`.

### `firstFrameSignal` and `deferredStartupTimeoutMs`

See [Priorities and deferred startup](priorities-and-deferred.md). A custom signal lives for the whole
process and must not retain an `Activity` or `Fragment`.

### `metricsReplayCapacity`

Bounds the replay of `metricsFlow` and also applies to live collectors: a collector that lags by more than
the capacity loses the oldest metrics. The drop warning is logged only with `debugLoggingEnabled`.

### `dispatcherInstrumentationEnabled`

Wraps each initializer's effective dispatcher so `dispatcherSnapshots()` and `StartupMetric.dispatcherName`
carry data. Wrapping `Dispatchers.IO` reduces its thread-pool elasticity and adds one `withContext` hop.

## Strategies

| `SyncOrderingStrategy` | Behavior |
|---|---|
| `Lazy` (default) | Discovery order, dependencies resolved depth-first. Cycle detected mid-initialization. |
| `Topological` | Kahn's algorithm before any `create()`. Cycles reported up front. |

| `AsyncInitializerStrategy` | Behavior |
|---|---|
| `Concurrent` (default) | One coroutine per discovered initializer; graph validated up front including transitive dependencies. |
| `Validated` | Validates the manifest-declared graph and launches only roots. |

Details and cycle messages: [Dependencies](dependencies.md).

## Next steps

- [Observability](../observability.md)
- [Testing](testing.md)
- [API reference: configuration](../api-reference.md#4-configuration)
