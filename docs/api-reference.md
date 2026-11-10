# API reference

Reference of the public API of `io.github.santimattius.android:app-startup-extension`, verified against
the committed ABI dump (`core/api/core.api`) and the sources. Everything not listed here (runners,
planner, engine, registry, tracer, metrics bus, `InstrumentedDispatcher`, `AndroidFirstFrameSignal`,
`StartupExtensionLogger`) is `internal` and may change without notice. For how the pieces fit together see
[Architecture](architecture.md).

## Table of contents

1. [3.0 notes](#1-30-notes)
2. [Initializers](#2-initializers)
   - [`StartupSyncInitializer<T>`](#startupsyncinitializert)
   - [`StartupAsyncInitializer<T>`](#startupasyncinitializert)
   - [`InitializationProvider`](#initializationprovider)
3. [Initialization manager and extension functions](#3-initialization-manager-and-extension-functions)
   - [`AppStartupInitializer`](#appstartupinitializer)
   - [Extension functions and properties](#extension-functions-and-properties)
   - [`StartupState`](#startupstate)
   - [`StartupExtensionException`](#startupextensionexception)
4. [Configuration](#4-configuration)
   - [`AppStartupConfig`](#appstartupconfig)
   - [Builder options and defaults](#builder-options-and-defaults)
   - [`SyncOrderingStrategy`](#syncorderingstrategy)
   - [`AsyncInitializerStrategy`](#asyncinitializerstrategy)
   - [`FirstFrameSignal`](#firstframesignal)
5. [Metrics and listeners](#5-metrics-and-listeners)
   - [`StartupMetric`](#startupmetric)
6. [Tracing and dispatcher snapshots](#6-tracing-and-dispatcher-snapshots)
   - [`DispatcherSnapshot`](#dispatchersnapshot)
   - [Trace sections](#trace-sections)
7. [Priorities](#7-priorities)
   - [`StartupPriority`](#startuppriority)

---

## 1. 3.0 notes

- **Behavior change (breaking):** `StartupSyncInitializer.create()` now runs on the **requesting thread**
  instead of always being dispatched to the main thread. Provider-driven startup still runs on main because
  Android calls `InitializationProvider.onCreate()` there. See the [migration guide](migration-3.0.md).
- Synchronous initializers are coordinated **per component** rather than through one global lock; same-component
  requests share one retained result and cross-thread dependency cycles fail with
  `StartupExtensionException` instead of hanging.
- The CHANGELOG lists no new public declarations for 3.0, and the library defines no opt-in or experimental
  annotations. Items below are tagged **Changed in 3.0** only where their documented behavior changed.
- Source-level thread assumptions in the 2.x KDoc (for example "main-thread only" for sync metrics) are
  superseded by this document.

## 2. Initializers

Package `io.github.santimattius.android.startup.initializer`. Implementations are declared in the manifest
as `<meta-data>` of `InitializationProvider` (value `sync-initializer` or `async-initializer`), must be
concrete classes and must have an accessible no-argument constructor.

### `StartupSyncInitializer<T>`

```kotlin
interface StartupSyncInitializer<T : Any> {
    fun create(context: Context): T
    fun dependencies(): List<Class<out StartupSyncInitializer<*>>> = emptyList()
    fun retainAfterStartup(): Boolean = true
}
```

A blocking initializer. The caller of the initialization is blocked until `create()` returns. **Changed in
3.0:** `create()` runs on the thread that requested initialization (main for provider-driven startup, the
calling thread for `initializeComponent` / `doInitialize`, the coroutine's thread for
`StartupAsyncInitializer.syncDependencies()`). There is no timeout, so provider-driven work must stay short
to avoid an ANR. Audit any main-thread-only API use inside `create()`.

| Member | Description |
|---|---|
| `create(context)` | Builds and returns the component. Receives the application context. |
| `dependencies()` | Sync initializer classes that must complete before this one. Default: empty. |
| `retainAfterStartup()` | `true` (default): the result is kept and shared by later requests. `false`: the result is released after initialization; requesting it again re-runs `create()`. |

```kotlin
class AnalyticsInitializer : StartupSyncInitializer<Analytics> {
    override fun create(context: Context): Analytics = Analytics.getInstance(context)
    override fun dependencies() = listOf(LoggerInitializer::class.java)
}
```

### `StartupAsyncInitializer<T>`

```kotlin
interface StartupAsyncInitializer<T : Any> {
    suspend fun create(context: Context): T
    fun dependencies(): List<Class<out StartupAsyncInitializer<*>>> = emptyList()
    fun syncDependencies(): List<Class<out StartupSyncInitializer<*>>> = emptyList()
    fun dispatcher(): CoroutineDispatcher? = null
    fun priority(): StartupPriority = StartupPriority.NORMAL
    fun retainAfterStartup(): Boolean = true
}
```

A suspending initializer run on the library's coroutine scope. A failure here does not cancel sibling
initializers; it surfaces when startup is awaited.

| Member | Description |
|---|---|
| `create(context)` | Suspending factory. Runs inside `withContext` of the resolved dispatcher. |
| `dependencies()` | Async initializer classes that must complete first. Default: empty. |
| `syncDependencies()` | Sync initializer classes that must complete before `create()`. Each runs at most once. **Changed in 3.0:** they run in the current coroutine context (not posted to main). Default: empty. |
| `dispatcher()` | Per-instance dispatcher. `null` (default) means "use `AppStartupConfig.defaultAsyncDispatcher`", which defaults to `Dispatchers.Default`. Precedence: this value, then the config default, then `Dispatchers.Default`. |
| `priority()` | Scheduling priority, default `StartupPriority.NORMAL`. See [Priorities](#7-priorities). |
| `retainAfterStartup()` | Same contract as the sync variant. |

```kotlin
class WarmupInitializer : StartupAsyncInitializer<Unit> {
    override suspend fun create(context: Context) { ImageCache.warmup(context) }
    override fun dispatcher() = Dispatchers.IO
    override fun priority() = StartupPriority.DEFERRED
    override fun retainAfterStartup() = false
}
```

### `InitializationProvider`

```kotlin
class InitializationProvider : ContentProvider()
```

The `ContentProvider` that boots the library. It is declared in the library manifest with
`android:authorities="${applicationId}.app-startup-extension"` and `android:exported="false"`; consumers add
their initializers as `<meta-data>` entries (`tools:node="merge"`). `onCreate()` runs on the main thread and
calls `AppStartupInitializer.getInstance(context).discoverAndInitialize(...)`. `query`, `getType`, `insert`,
`delete` and `update` throw `IllegalStateException("Not allowed.")`. Not intended to be subclassed or called.

```xml
<provider
    android:name="io.github.santimattius.android.startup.InitializationProvider"
    android:authorities="${applicationId}.startup-extension"
    android:exported="false"
    tools:node="merge">
    <meta-data android:name="com.example.AnalyticsInitializer" android:value="sync-initializer" />
    <meta-data android:name="com.example.WarmupInitializer" android:value="async-initializer" />
</provider>
```

## 3. Initialization manager and extension functions

### `AppStartupInitializer`

```kotlin
class AppStartupInitializer  // constructor is internal; use getInstance
```

The singleton facade. Obtain it with `AppStartupInitializer.getInstance(context)`.

| Member | Signature | Description |
|---|---|---|
| `initializeComponent` | `fun <T : Any> initializeComponent(component: Class<out StartupSyncInitializer<T>>): T` | Initializes (or returns the retained result of) a sync component. Equivalent to `doInitialize(component)`. Blocks the calling thread; **Changed in 3.0:** `create()` runs on the calling thread. |
| `doInitialize` (sync) | `fun <T> doInitialize(component: Class<out StartupSyncInitializer<*>>): T` | Same as above with an unchecked result type. Throws `StartupExtensionException` on failure and `ClassCastException` if `T` does not match. Coordinated per component; concurrent callers for the same component share one result. |
| `doInitialize` (async) | `suspend fun <T> doInitialize(component: Class<out StartupAsyncInitializer<*>>): T` | Initializes an async component on demand and returns the retained result when already initialized. On-demand async calls are serialized by one mutex. |
| `isEagerlySyncInitialized` | `fun isEagerlySyncInitialized(component: Class<out StartupSyncInitializer<*>>): Boolean` | `true` if the class was discovered from manifest metadata at startup. |
| `isEagerlyAsyncInitialized` | `fun isEagerlyAsyncInitialized(component: Class<out StartupAsyncInitializer<*>>): Boolean` | Same for async classes. |
| `metricsFlow` | `val metricsFlow: SharedFlow<StartupMetric>` | Hot, bounded flow of one `StartupMetric` per finished initializer. Late collectors get the last `metricsReplayCapacity` (default 256) metrics; lagging live collectors lose the oldest (`DROP_OLDEST`); emitters never suspend. Collect from a lifecycle-aware scope. |
| `dispatcherSnapshots` | `fun dispatcherSnapshots(): Map<String, DispatcherSnapshot>` | Per-dispatcher counters keyed by label. Empty unless `dispatcherInstrumentationEnabled` is `true`. |

Companion object:

| Member | Signature | Description |
|---|---|---|
| `getInstance` | `fun getInstance(context: Context): AppStartupInitializer` | Thread-safe singleton accessor (double-checked locking). |
| `configure` | `fun configure(block: AppStartupConfig.Builder.() -> Unit)` | Builds a new `AppStartupConfig` from the block, replaces the library-wide configuration and enables or disables debug logging according to `debugLoggingEnabled`. Call it before `InitializationProvider` runs, e.g. in `Application.attachBaseContext`. `maxConcurrentAsyncInitializers` and `metricsReplayCapacity` are read once when the singleton is created, so a later `configure` does not change them for the running instance; the other options are read on each use. |

```kotlin
class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AppStartupInitializer.configure {
            debugLoggingEnabled = BuildConfig.DEBUG
            syncOrderingStrategy = SyncOrderingStrategy.Topological
        }
    }
}

val analytics = AppStartupInitializer.getInstance(context)
    .initializeComponent(AnalyticsInitializer::class.java)

val db = AppStartupInitializer.getInstance(context)
    .doInitialize<Database>(DatabaseInitializer::class.java) // DatabaseInitializer: StartupSyncInitializer
```

### Extension functions and properties

Defined in `AppStartupExtension.kt` (file class `AppStartupExtensionKt`).

| Member | Signature | Description |
|---|---|---|
| `awaitAllStartJobs` | `suspend fun AppStartupInitializer.awaitAllStartJobs()` | Suspends until all start jobs (eager roots) finish. Does **not** wait for `DEFERRED` roots. Sets `startupState` to `COMPLETED`, or to `ERROR` and rethrows the first failure. |
| `awaitAllStartJobs` | `suspend fun AppStartupInitializer.awaitAllStartJobs(timeoutMs: Long)` | Same with a timeout. On timeout throws `TimeoutCancellationException` and cancels start jobs still active. |
| `onAppStartupLaunched` | `suspend fun AppStartupInitializer.onAppStartupLaunched(block: suspend (AppStartupInitializer) -> Unit)` | Runs `awaitAllStartJobs()` then `block(this)`. Propagates a startup failure. |
| `isAllStartedJobsDone` | `fun AppStartupInitializer.isAllStartedJobsDone(): Boolean` | Non-suspending: `true` when no tracked start job is active (completed, failed or cancelled). Deferred jobs are not tracked. |
| `startupState` | `val AppStartupInitializer.startupState: StateFlow<StartupState>` | Hot flow of the lifecycle state. |

```kotlin
lifecycleScope.launch {
    val startup = AppStartupInitializer.getInstance(applicationContext)
    startup.onAppStartupLaunched { showHome() }
}

val state: StateFlow<StartupState> = AppStartupInitializer.getInstance(context).startupState
```

### `StartupState`

```kotlin
enum class StartupState { IDLE, IN_PROGRESS, COMPLETED, ERROR }
```

| Value | Meaning |
|---|---|
| `IDLE` | No start job has been launched. |
| `IN_PROGRESS` | At least one start job was launched. |
| `COMPLETED` | `awaitAllStartJobs` finished without a failure. |
| `ERROR` | `awaitAllStartJobs` observed a non-cancellation failure. Sticky: a later `COMPLETED` does not replace it. |

The state only moves to `COMPLETED` / `ERROR` when startup is awaited.

### `StartupExtensionException`

```kotlin
class StartupExtensionException(message: String?, cause: Throwable?) : Exception(message, cause) {
    constructor(message: String?)
    constructor(cause: Throwable?)
    constructor()
}
```

The library's error type: discovery failures, abstract or interface initializers, dependency cycles
(including cross-thread sync cycles) and wrapped `create()` failures. Async `CancellationException`s are
never wrapped.

## 4. Configuration

### `AppStartupConfig`

```kotlin
class AppStartupConfig private constructor(/* read-only properties */) {
    class Builder { /* mutable options */ }
}
```

Immutable snapshot of the configuration. It has no public constructor: configure the library with
`AppStartupInitializer.configure { ... }`. All properties listed below are readable on `AppStartupConfig` and
settable on `AppStartupConfig.Builder` (public `Builder()` constructor).

### Builder options and defaults

| Option | Type | Default | Description |
|---|---|---|---|
| `strictModeCheckEnabled` | `Boolean` | `false` | Runs each sync `create()` under a `StrictMode.ThreadPolicy` (`detectAll().penaltyLog()`), restoring the previous policy afterwards. Also enables the async concurrency-threshold warning (which additionally needs `debugLoggingEnabled`). |
| `debugLoggingEnabled` | `Boolean` | `false` | Enables library log output (tag `StartupLogger`) for discovery, initialization, dropped metrics and strict-mode warnings. Messages are built lazily. |
| `syncOrderingStrategy` | `SyncOrderingStrategy` | `SyncOrderingStrategy.Lazy` | How sync initializers are ordered at startup. |
| `asyncInitializerStrategy` | `AsyncInitializerStrategy` | `AsyncInitializerStrategy.Concurrent` | How async initializers are validated and launched. |
| `maxConcurrentAsyncInitializers` | `Int?` | `null` (unbounded) | Maximum number of async `create()` bodies running at once. Values `<= 0` are normalized to `null`. Read when the singleton is created. |
| `defaultAsyncDispatcher` | `CoroutineDispatcher` | `Dispatchers.Default` | Dispatcher for async initializers whose `dispatcher()` returns `null`. |
| `strictModeConcurrencyThreshold` | `Int` | `Runtime.getRuntime().availableProcessors()` | Active-async count above which one strict-mode warning is logged. |
| `firstFrameSignal` | `FirstFrameSignal?` | `null` | Custom signal gating `DEFERRED` initializers. `null` uses the internal Android first-draw signal, created lazily. |
| `deferredStartupTimeoutMs` | `Long` | `5_000L` | Maximum wait for the first frame before `DEFERRED` work is flushed anyway. A value `<= 0` flushes immediately. |
| `metricsReplayCapacity` | `Int` | `256` | Replay and buffer bound of `metricsFlow`. Values `<= 0` fall back to `256`. Read when the singleton is created. |
| `dispatcherInstrumentationEnabled` | `Boolean` | `false` | Debug/measurement only. Wraps each initializer's effective dispatcher to fill `dispatcherSnapshots()` and `StartupMetric.dispatcherName`. Do not enable in production. |

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

### `SyncOrderingStrategy`

```kotlin
sealed class SyncOrderingStrategy {
    object Lazy : SyncOrderingStrategy()
    object Topological : SyncOrderingStrategy()
}
```

- `Lazy` (default): discovery order, each initializer resolving its dependencies depth-first. Cycles are
  detected mid-initialization.
- `Topological`: Kahn's algorithm before any `create()`; cycles are reported up front with all participating
  names and redundant dependency calls are avoided.

### `AsyncInitializerStrategy`

```kotlin
sealed class AsyncInitializerStrategy {
    object Concurrent : AsyncInitializerStrategy()
    object Validated : AsyncInitializerStrategy()
}
```

- `Concurrent` (default): every discovered async initializer gets its own coroutine. The graph is
  validated for cycles up front, including transitive dependencies that are not in the manifest.
- `Validated`: validates the manifest-declared async graph and launches only **root** initializers; their
  dependencies are resolved inside the root's coroutine.

`syncDependencies()` are excluded from the async graph in both strategies.

### `FirstFrameSignal`

```kotlin
interface FirstFrameSignal {
    suspend fun await()
}
```

A seam that resolves when the first frame has been drawn. `DEFERRED` initializers suspend on it before
`create()`. Implementations must be safe to `await()` from several coroutines and must resume all of them
once signalled; returning immediately means "already drawn". A custom instance lives for the whole process
through `AppStartupConfig`, so it must not retain an `Activity` or `Fragment`. Mainly useful for tests
(a `CompletableDeferred`-backed fake) or to tie deferral to a specific screen becoming interactive.

```kotlin
class ScreenReadySignal(private val ready: CompletableDeferred<Unit>) : FirstFrameSignal {
    override suspend fun await() = ready.await()
}

AppStartupInitializer.configure { firstFrameSignal = ScreenReadySignal(readyDeferred) }
```

## 5. Metrics and listeners

The library exposes metrics through `AppStartupInitializer.metricsFlow` (a `SharedFlow`); there is no
separate listener interface.

### `StartupMetric`

```kotlin
data class StartupMetric @JvmOverloads constructor(
    val initializerName: String,
    val durationMs: Long,
    val isAsync: Boolean,
    val success: Boolean,
    val wasCancelled: Boolean = false,
    val dispatcherName: String = "",
    val threadName: String = "",
    val concurrentActiveCount: Int = 0,
    val queueDelayMs: Long = 0L,
    val startTimestampMs: Long = 0L,
    val endTimestampMs: Long = 0L,
    val processStartOffsetMs: Long = 0L,
    val priority: StartupPriority = StartupPriority.NORMAL,
    val initializerFqn: String = "",
    val errorType: String? = null,
    val errorMessage: String? = null,
    val permitWaitMs: Long = 0L,
)
```

Timing and outcome of one initializer execution, published when it finishes (success or failure).

| Field | Description |
|---|---|
| `initializerName` | `Class.simpleName` of the initializer. |
| `durationMs` | Monotonic milliseconds spent in `create()`. |
| `isAsync` | `true` for async initializers. |
| `success` | `true` if `create()` completed without throwing. |
| `wasCancelled` | `true` if structured cancellation interrupted it; `success` is then `false`. A cancellation is not an error. |
| `dispatcherName` | Async only: `toString()` of the effective dispatcher (a stable derived label when instrumentation is enabled). Empty for sync. |
| `threadName` | Async only: thread the body ran on. Empty for sync (and for async cancelled before `create()` started). |
| `concurrentActiveCount` | Async only: number of async `create()` bodies active when this one started, itself included. `0` for sync. |
| `queueDelayMs` | Async only: from ready-to-run until the body started (permit wait plus dispatcher scheduling). If cancelled before starting, measured up to the cancellation, so never below `permitWaitMs`. `0` for sync. |
| `startTimestampMs` / `endTimestampMs` | `SystemClock.uptimeMillis()` at start and `start + duration`. `0` when unknown (async cancelled before `create()` started). |
| `processStartOffsetMs` | Milliseconds from process start to `startTimestampMs`; never negative. |
| `priority` | The **declared** `StartupPriority` (not the clamped effective one). Sync initializers report `NORMAL`. |
| `initializerFqn` | Fully-qualified class name; matches trace section names. |
| `errorType` / `errorMessage` | Class name and message of the failure; `null` on success and on cancellation. No `Throwable` is retained. |
| `permitWaitMs` | Async only: time waiting for the concurrency permit; `0` without a cap. `queueDelayMs - permitWaitMs` approximates dispatcher delay. |

The `@JvmOverloads` constructors keep earlier constructor shapes; the generated `copy` / `componentN`
signatures include every field, so code compiled against an older version must be recompiled.

```kotlin
lifecycleScope.launch {
    AppStartupInitializer.getInstance(context).metricsFlow.collect { m ->
        Log.d("Startup", "${m.initializerFqn} ${m.durationMs}ms async=${m.isAsync} ok=${m.success}")
    }
}
```

## 6. Tracing and dispatcher snapshots

### `DispatcherSnapshot`

```kotlin
data class DispatcherSnapshot(
    val label: String,
    val peakConcurrency: Int,
    val maxQueueDelayMs: Long,
    val totalDispatched: Long,
)
```

Point-in-time counters of one instrumented dispatcher, returned by `AppStartupInitializer.dispatcherSnapshots()`
(only populated when `dispatcherInstrumentationEnabled = true`).

| Field | Description |
|---|---|
| `label` | Key derived from the dispatcher's `toString()` with identity hash, bracketed pool statistics and package prefix removed (for example `ThreadPoolExecutor`, `Dispatchers.IO`). Distinct dispatchers with the same label get a `#n` suffix. It cannot be set by the caller. |
| `peakConcurrency` | Highest number of dispatched blocks running at the same time. |
| `maxQueueDelayMs` | Longest wait between dispatch and start. Dispatcher latency only; the library's permit wait is `StartupMetric.permitWaitMs`. |
| `totalDispatched` | Number of blocks dispatched through the dispatcher. |

```kotlin
AppStartupInitializer.configure { dispatcherInstrumentationEnabled = BuildConfig.DEBUG }
// later
val snapshots: Map<String, DispatcherSnapshot> =
    AppStartupInitializer.getInstance(context).dispatcherSnapshots()
```

### Trace sections

There is no public tracing API: `StartupTracer` and `StartupTraceNames` are internal. The observable
contract is the set of `android.os.Trace` names the library writes, of the form `Startup::<phase>::<FQN>`
(capped at 127 characters; the FQN is shortened from the left). Examples: `Startup::<FQN>`,
`Startup::create::<FQN>`, `Startup::permitWait::<FQN>`, `Startup::awaitAll`, `Startup::onDemand::<FQN>`,
`Startup::marker::COMPLETED`; counters `Startup::activeAsync`, `Startup::permitsWaiting`,
`Startup::permitsAvailable` and `Startup::busSize` (API 29+). The outer provider slice is named
`StartupExtension`. See [Observability](observability.md#trace-sections) for the complete table.

## 7. Priorities

### `StartupPriority`

```kotlin
enum class StartupPriority { CRITICAL, NORMAL, DEFERRED }
```

Declared on `StartupAsyncInitializer.priority()`. Declaration order doubles as an eagerness rank
(`CRITICAL` = 0 is most eager).

| Value | Behavior |
|---|---|
| `CRITICAL` | Reserved for a future strict scheduler. Currently behaves **identically to `NORMAL`**: launched eagerly, subject to the concurrency cap, not ordered ahead of `NORMAL`. |
| `NORMAL` | Default. Launched immediately at startup. |
| `DEFERRED` | Launched only after the `FirstFrameSignal` resolves or `deferredStartupTimeoutMs` elapses. Not awaited by `awaitAllStartJobs()`. |

If an eager initializer depends, directly or transitively, on a `DEFERRED` one, the dependency's effective
priority is clamped to the most eager dependent and a one-time `Log.w` warning is emitted (priority
inversion). The first-frame gate applies to the launched roots (every discovered async initializer under
`Concurrent`, only roots under `Validated`) by effective priority, so a `DEFERRED` initializer that an eager
one depends on runs eagerly.

```kotlin
class PrefetchInitializer : StartupAsyncInitializer<Unit> {
    override suspend fun create(context: Context) { Prefetcher.run(context) }
    override fun priority() = StartupPriority.DEFERRED
}
```
