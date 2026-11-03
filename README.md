![architecture - Startup Extension Initializer(1)](https://github.com/user-attachments/assets/9d44a786-7638-44de-952a-60059984abda)

[![Latest Release](https://maven-badges.sml.io/sonatype-central/io.github.santimattius.android/app-startup-extension/badge.svg?subject=Latest%20Release&color=blue)](https://maven-badges.sml.io/sonatype-central/io.github.santimattius.android/app-startup-extension/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

# App Startup Extension

App Startup Extension is a library based on AndroidX App Startup that optimizes component
initialization in Android applications using Kotlin Coroutines. Its primary goal is to improve
startup time without blocking the main thread.

## Features

- **Synchronous Initialization:** Allows components to be initialized synchronously.
- **Asynchronous Initialization:** Supports asynchronous component initialization to avoid blocking
  the main thread.
- **Startup State Lifecycle:** Observe the startup lifecycle (`IDLE → IN_PROGRESS → COMPLETED / ERROR`) via a `StateFlow`.
- **Timeout Support:** Await all startup jobs with a configurable timeout.
- **Metrics Flow:** Per-initializer timing, outcome, and concurrency/dispatcher data exposed as a hot `SharedFlow` — late collectors receive a bounded replay (the most recent `metricsReplayCapacity` metrics, 256 by default).
- **Coroutine Storm Mitigation:** Optional cap on concurrent async `create()` executions (`maxConcurrentAsyncInitializers`) backed by a deadlock-safe `Semaphore`, plus a library-wide default dispatcher (`defaultAsyncDispatcher`) and a strict-mode warning when concurrency exceeds a configurable threshold.
- **Startup Priority Staggering:** Mark an async initializer `DEFERRED` via `priority()` to launch it only after the first frame is drawn (or a timeout fallback), keeping non-critical work off the startup critical path.
- **Cancellation Reporting:** `StartupMetric.wasCancelled` distinguishes structured cancellations from real failures.
- **Transient Initializers:** Override `retainAfterStartup()` to `false` to release the initializer's result from the registry after startup, enabling GC of side-effect-only components.
- **Configurable Sync Ordering:** Choose between `SyncOrderingStrategy.Lazy` (default DFS) or `Topological` (Kahn's algorithm with upfront cycle detection before any `create()` runs).
- **Configurable Async Launch Strategy:** Choose between `AsyncInitializerStrategy.Concurrent` (default, one coroutine per initializer) or `Validated` (graph validation + root-only coroutine launch, reducing coroutine count for chained initializers).
- **Unified Configuration DSL:** All runtime options consolidated into `AppStartupInitializer.configure { }`.
- **StrictMode Integration:** Opt-in StrictMode check that detects blocking I/O inside sync initializers during development.
- **Debug Logging:** Enable or disable library log output at runtime.
- **Cross-type Dependencies:** Async initializers can now declare sync initializer dependencies.
- **Per-initializer Dispatcher:** Each async initializer can specify its own `CoroutineDispatcher`.
- **R8/ProGuard Rules:** Consumer keep rules are bundled — no manual configuration required.
- **System Tracing:** `Startup::<phase>::<FQN>` slices, counters and markers on the system trace, viewable in Perfetto and measurable with Macrobenchmark (see [Observability](#observability-tracing--metrics)).

## Installation

To add this library to your Android project, include the repository in your project-level
`build.gradle` file:

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
```

Then, add the dependency in your module-level `build.gradle` file:

```groovy
dependencies {
    implementation("io.github.santimattius.android:app-startup-extension:${version}")
}
```

Replace `version` with the desired library version.

## Usage

To define an initializer, implement `StartupSyncInitializer` or `StartupAsyncInitializer`.

### Example: `StartupSyncInitializer`

```kotlin
class SyncTestInitializer : StartupSyncInitializer<Unit> {
    override fun create(context: Context) {
        Log.d("SyncTestInitializer", "TestInitializer created")
    }
}
```

> **Note:** `StartupSyncInitializer` runs on the main thread during app startup.

### Example: `StartupAsyncInitializer`

```kotlin
class AsyncTestInitializer : StartupAsyncInitializer<Unit> {
    override suspend fun create(context: Context) {
        Log.d("AsyncTestInitializer", "AsyncTestInitializer start")
        delay(5000)
        Log.d("AsyncTestInitializer", "AsyncTestInitializer end")
    }
}
```

#### Per-initializer Dispatcher

Override `dispatcher()` to pin the initializer's `create()` to a specific thread pool. `dispatcher()` returns
`CoroutineDispatcher?` — an unoverridden (or `null`-returning) initializer falls back to
`AppStartupConfig.defaultAsyncDispatcher` (itself `Dispatchers.Default` unless configured).

```kotlin
class DatabaseInitializer : StartupAsyncInitializer<Unit> {
    override fun dispatcher() = Dispatchers.IO

    override suspend fun create(context: Context) {
        // runs on Dispatchers.IO
    }
}
```

#### Cross-type Dependencies

An async initializer can declare that certain sync initializers must complete before its `create()` is called by overriding `syncDependencies()`.

```kotlin
class AnalyticsInitializer : StartupAsyncInitializer<Unit> {
    override fun syncDependencies() = listOf(ConfigInitializer::class.java)

    override suspend fun create(context: Context) {
        // ConfigInitializer.create() is guaranteed to have finished
    }
}
```

#### Priority Staggering (Deferred Initializers)

Override `priority()` to control **when** an async initializer launches. The default,
`StartupPriority.NORMAL`, launches immediately on the eager critical path — unchanged for every
existing initializer. Return `StartupPriority.DEFERRED` for work that is safe to run **after the
first frame is drawn** (analytics warmers, prefetchers) so it never competes with UI-critical startup.

```kotlin
class AnalyticsWarmupInitializer : StartupAsyncInitializer<Unit> {
    override fun priority() = StartupPriority.DEFERRED

    override suspend fun create(context: Context) {
        // Runs only after the first frame is drawn (or the deferred timeout elapses)
    }
}
```

> **Note:** `StartupPriority.CRITICAL` is reserved for a future strict scheduler — in this release it
> behaves identically to `NORMAL`. `awaitAllStartJobs()` excludes `DEFERRED` jobs, so it returns
> without waiting for deferred work to finish (see [Startup Priority Staggering](#startup-priority-staggering)).

### Registering in `AndroidManifest.xml`

```xml

<provider android:authorities="${applicationId}.startup-extension" android:exported="false"
    android:name="io.github.santimattius.android.startup.InitializationProvider" tools:node="merge">

    <meta-data android:name="com.santimattius.android.sample.SyncTestInitializer"
        android:value="sync-initializer" />

    <meta-data android:name="com.santimattius.android.sample.AsyncTestInitializer"
        android:value="async-initializer" />
</provider>
```

## Manual Initialization

You can also manually initialize components:

```kotlin
val initializer = AppStartupInitializer.getInstance(this@MainActivity)

// Synchronous initialization
initializer.doInitialize<Unit>(SyncTestInitializer::class.java)

// Asynchronous initialization
coroutineScope.launch {
    initializer.doInitialize<Unit>(AsyncTestInitializer::class.java)
}
```

## Utility Functions

### awaitAllStartJobs()

Suspends until all asynchronous initialization tasks started by `AppStartupInitializer` are completed.

```kotlin
val appStartupInitializer = AppStartupInitializer.getInstance(appContext)

coroutineScope.launch {
    appStartupInitializer.awaitAllStartJobs()
}
```

### awaitAllStartJobs(timeoutMs)

Same as above but throws `TimeoutCancellationException` if the jobs do not complete within the given
number of milliseconds. The underlying jobs continue running in the background — only the wait is cancelled.

```kotlin
coroutineScope.launch {
    appStartupInitializer.awaitAllStartJobs(timeoutMs = 5_000)
}
```

### onAppStartupLaunched

Executes a given block of code only after all startup jobs have finished. This is useful for running
code that depends on the completion of initialization processes.

```kotlin
coroutineScope.launch {
    appStartupInitializer.onAppStartupLaunched {
        Log.d("AppStartup", "All initialization tasks completed")
    }
}
```

### isAllStartedJobsDone

Checks if all initialization jobs managed by `AppStartupInitializer` are completed. Returns `true` if
no jobs are currently active, `false` otherwise.

```kotlin
if (appStartupInitializer.isAllStartedJobsDone()) {
    Log.d("AppStartup", "All startup jobs have finished")
}
```

### startupState

A hot `StateFlow<StartupState>` that reflects the current startup lifecycle. Collect it to react to
state changes without polling.

```
IDLE → IN_PROGRESS → COMPLETED
                   ↘ ERROR       (on first job failure; sibling jobs continue)
```

```kotlin
coroutineScope.launch {
    appStartupInitializer.startupState.collect { state ->
        when (state) {
            StartupState.COMPLETED -> Log.d("AppStartup", "Startup finished")
            StartupState.ERROR     -> Log.e("AppStartup", "Startup failed")
            else -> Unit
        }
    }
}
```

### Metrics

`metricsFlow` is a hot `SharedFlow<StartupMetric>` that emits after each initializer completes
(success or failure). Values are **replayed** (up to `metricsReplayCapacity`) — a collector that joins after startup finishes
will still receive the metrics from the startup sequence, up to the replay bound (see
[Observability](#observability-tracing--metrics)).

```kotlin
lifecycleScope.launch {
    AppStartupInitializer.getInstance(context).metricsFlow.collect { metric ->
        Log.d(
            "Metrics",
            "${metric.initializerName} took ${metric.durationMs}ms " +
            "(async=${metric.isAsync}, success=${metric.success}, " +
            "cancelled=${metric.wasCancelled}, dispatcher=${metric.dispatcherName}, " +
            "thread=${metric.threadName}, concurrentActive=${metric.concurrentActiveCount}, " +
            "queueDelayMs=${metric.queueDelayMs})"
        )
    }
}
```

`StartupMetric` fields:

| Field | Description |
|---|---|
| `initializerName` | `Class.simpleName` of the initializer |
| `durationMs` | Monotonic milliseconds elapsed during `create()` |
| `isAsync` | `true` for async initializers, `false` for sync |
| `success` | `true` if `create()` completed without throwing |
| `wasCancelled` | `true` if interrupted by structured cancellation (not a real failure) |
| `dispatcherName` | Dispatcher `create()` actually ran on (empty for sync) |
| `threadName` | Thread `create()` actually ran on (empty for sync) |
| `concurrentActiveCount` | Snapshot of how many async initializers were executing `create()` when this one started (always `0` for sync) |
| `queueDelayMs` | Time from becoming ready to run to actually starting — concurrency-cap wait + dispatcher scheduling latency; the primary "coroutine storm" signal (always `0` for sync) |
| `permitWaitMs` | Time spent waiting for the concurrency permit (`0` for sync or without a cap); `queueDelayMs - permitWaitMs` approximates dispatcher scheduling delay |
| `startTimestampMs` / `endTimestampMs` | `SystemClock.uptimeMillis()` when `create()` started / ended; `0` when unknown (e.g. cancelled before `create()` started) |
| `processStartOffsetMs` | Milliseconds from process start to `startTimestampMs` |
| `priority` | Declared `StartupPriority` (sync initializers report `NORMAL`) |
| `initializerFqn` | Fully-qualified class name; matches the trace section names |
| `errorType` / `errorMessage` | Throwable class name and message of a failed `create()`; `null` on success and cancellation. Only strings are kept, never the throwable |

> **Note:** Collect from a lifecycle-aware scope (e.g. `lifecycleScope`) to avoid retaining
> UI references in the singleton.

### Transient Initializers

By default, the result of `create()` is held in an internal registry for the lifetime of the app.
Override `retainAfterStartup()` to `false` when an initializer only exists for its side effects and
its result has no value after startup. The library will drop the reference immediately after `create()`
finishes, allowing the GC to collect the object.

```kotlin
class CacheWarmupInitializer : StartupAsyncInitializer<Unit> {
    override suspend fun create(context: Context) {
        ImageCache.warmup(context)
    }
    override fun retainAfterStartup(): Boolean = false
}
```

```kotlin
class MigrationInitializer : StartupSyncInitializer<Unit> {
    override fun create(context: Context) {
        DatabaseMigrationRunner(context).runPendingMigrations()
    }
    override fun retainAfterStartup(): Boolean = false
}
```

> **Note:** If a transient initializer is requested again post-startup via `initializeComponent`,
> `create()` will be re-executed. Ensure `create()` is safe to call more than once, or avoid
> requesting transient initializers after startup.

### Configuration

All runtime options are consolidated into a single `configure { }` DSL. Call it before
`InitializationProvider` runs — the earliest safe point is `Application.attachBaseContext`.

```kotlin
class MyApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AppStartupInitializer.configure {
            debugLoggingEnabled    = BuildConfig.DEBUG
            strictModeCheckEnabled = BuildConfig.DEBUG
            syncOrderingStrategy   = SyncOrderingStrategy.Topological
            asyncInitializerStrategy = AsyncInitializerStrategy.Validated
        }
    }
}
```

| Option | Type | Default | Description |
|---|---|---|---|
| `debugLoggingEnabled` | `Boolean` | `false` | Enables library log output (discovery + init messages) |
| `strictModeCheckEnabled` | `Boolean` | `false` | Wraps each sync `create()` with a `StrictMode.ThreadPolicy` that surfaces blocking I/O violations in logcat |
| `syncOrderingStrategy` | `SyncOrderingStrategy` | `Lazy` | Controls how sync initializers are ordered before execution |
| `asyncInitializerStrategy` | `AsyncInitializerStrategy` | `Concurrent` | Controls how async initializers are validated and launched |
| `maxConcurrentAsyncInitializers` | `Int?` | `null` | Caps how many async `create()` bodies may run concurrently. `null` or values `<= 0` mean unbounded |
| `defaultAsyncDispatcher` | `CoroutineDispatcher` | `Dispatchers.Default` | Library-wide default dispatcher for async initializers that don't override `dispatcher()` |
| `strictModeConcurrencyThreshold` | `Int` | `Runtime.getRuntime().availableProcessors()` | Concurrency level above which strict mode logs a warning (only when `strictModeCheckEnabled` is on) |
| `firstFrameSignal` | `FirstFrameSignal?` | `null` | Optional injectable signal gating `DEFERRED` initializers. `null` uses the internal Android first-draw default; inject a fake to drive deferred scheduling under test |
| `deferredStartupTimeoutMs` | `Long` | `5_000L` | Upper bound the deferred gate waits for the first frame before flushing `DEFERRED` work anyway (headless/no-UI fallback). Values `<= 0` flush immediately |
| `metricsReplayCapacity` | `Int` | `256` | How many recent metrics `metricsFlow` replays to late collectors. Values `<= 0` use the default |
| `dispatcherInstrumentationEnabled` | `Boolean` | `false` | Debug/measurement only: wraps each initializer's effective dispatcher to expose `dispatcherSnapshots()` (see [Observability](#observability-tracing--metrics)) |

#### `SyncOrderingStrategy`

| Value | Behaviour |
|---|---|
| `Lazy` *(default)* | Existing DFS recursive resolution. Cycle detected mid-initialization — some `create()` calls may have already run when the exception is thrown. |
| `Topological` | Kahn's algorithm validates the full dependency graph before any `create()` is called. Cycles are reported upfront with all participating node names. Eliminates redundant `doInitialize` calls for already-initialized components. |

#### `AsyncInitializerStrategy`

| Value | Behaviour |
|---|---|
| `Concurrent` *(default)* | One coroutine launched per discovered async initializer. Cycle detected lazily inside each coroutine's DFS chain. |
| `Validated` | Builds the async-to-async dependency graph (from `dependencies()` only — `syncDependencies()` are already resolved by the time async initializers start) before launching any coroutine. Cycles detected upfront. Only **root** nodes — those no other async initializer depends on — receive an explicit coroutine; their dependencies are resolved recursively inside that coroutine, reducing total coroutine count for chained initializers. |

#### Coroutine Storm Mitigation

Launching every async initializer at once on `Dispatchers.Default` can flood the shared thread pool
("coroutine storm"), starving other work at startup. `maxConcurrentAsyncInitializers` caps how many
`create()` bodies run at the same time via a `Semaphore`, acquired only *after* an initializer's
dependencies have already completed — so a parent never holds a permit while waiting on a child,
avoiding deadlock even at a cap of `1`.

```kotlin
AppStartupInitializer.configure {
    maxConcurrentAsyncInitializers = 4
    defaultAsyncDispatcher = Dispatchers.IO
    strictModeCheckEnabled = BuildConfig.DEBUG
    strictModeConcurrencyThreshold = 4
}
```

When `strictModeCheckEnabled` is on, the library logs a single warning per startup if the observed
`concurrentActiveCount` ever exceeds `strictModeConcurrencyThreshold`, reporting the peak, the
threshold, the dispatcher involved, and remediation guidance. Use the `queueDelayMs` and
`concurrentActiveCount` fields on `StartupMetric` (see [Metrics](#metrics)) to measure the storm
before deciding on a cap.

#### Startup Priority Staggering

`StartupPriority` controls **when** an async initializer's `create()` is launched:

| Priority | Ordinal | Meaning |
|---|---|---|
| `CRITICAL` | 0 | Most eager. In this release behaves identically to `NORMAL`; reserved for a future strict scheduler. |
| `NORMAL` *(default)* | 1 | Launched immediately on the eager critical path. |
| `DEFERRED` | 2 | Launched only after the first frame is drawn (or `deferredStartupTimeoutMs` elapses). |

```kotlin
AppStartupInitializer.configure {
    deferredStartupTimeoutMs = 5_000L
    // firstFrameSignal = MyFirstFrameSignal() // optional custom trigger (default: Android first-draw)
}

class AnalyticsWarmupInitializer : StartupAsyncInitializer<Unit> {
    override fun priority() = StartupPriority.DEFERRED

    override suspend fun create(context: Context) {
        // Never competes with UI-critical startup for the first frame
    }
}
```

**Priority inversion:** if an eager (`NORMAL`/`CRITICAL`) initializer depends — directly or
transitively — on a `DEFERRED` one, the library clamps the `DEFERRED` dependency's effective priority
up to its most-eager dependent (so it launches eagerly instead of stalling the dependent past the
first frame) and logs a single warning per startup.

**`awaitAllStartJobs()` excludes `DEFERRED` jobs** — the critical-path await returns without waiting
for deferred work, so it cannot block on cap `1` or under `runBlocking`.

### StrictMode Integration

Wraps each `StartupSyncInitializer.create()` invocation with a `StrictMode.ThreadPolicy` that detects
accidental disk I/O, network access, or any other blocking operation on the main thread. Violations
appear as `StrictMode` entries in logcat.

The original policy is always restored after each `create()` call, so this does not affect the app's
own StrictMode configuration.

```kotlin
AppStartupInitializer.configure {
    strictModeCheckEnabled = BuildConfig.DEBUG
}
```

### Debug Logging

Enable library log output to capture discovery and initialization messages.
Logging is **disabled by default**.

```kotlin
AppStartupInitializer.configure {
    debugLoggingEnabled = BuildConfig.DEBUG
}
```

## Observability (Tracing & Metrics)

### System trace sections

The library writes `android.os.Trace` slices, so they show up in any system trace. Names follow
`Startup::<phase>::<FQN>` (`<FQN>` is the initializer's fully-qualified class name; names are capped at
127 characters by keeping the prefix and shortening the FQN from the left), so the wildcard `Startup::%`
matches all of them except the outer `StartupExtension` slice, which has no prefix. Sections are **sync** (thread slices) when the block cannot suspend and **async**
(process-track slices, API 29+) when it can; on API < 29 only the sync ones are emitted.

| Section | Kind | Meaning |
|---|---|---|
| `StartupExtension` | sync | Outer slice around the whole provider-driven discovery and initialization (does **not** match `Startup::%`) |
| `Startup::getProviderInfo` | sync | The blocking `PackageManager.getProviderInfo` IPC call made at startup |
| `Startup::<FQN>` | sync / async | Whole initializer node (sync or async initializer) |
| `Startup::discover::sync\|async` | sync | Reading manifest metadata and finding initializers |
| `Startup::resolveGraph::topological\|validateRoots\|effectivePriorities` | sync | Dependency graph validation and priority resolution |
| `Startup::instantiate::<FQN>` | sync | Reflective construction of the initializer |
| `Startup::deps::<FQN>` | sync / async | Waiting for / resolving its dependencies |
| `Startup::permitWait::<FQN>` | async | Waiting for a `maxConcurrentAsyncInitializers` permit |
| `Startup::create::<FQN>` | sync / async | The `create()` body itself |
| `Startup::lockWait::sync`, `Startup::lockWait::async::<FQN>` | sync / async | Waiting for the sync lock / async mutex that serializes initialization |
| `Startup::awaitAll` | async | `awaitAllStartJobs()` / `onAppStartupLaunched` waiting for the critical path |
| `Startup::onDemand::<FQN>` | sync / async | Public `initializeComponent` / `doInitialize` calls (startup-driven work is not wrapped) |
| `Startup::deferred::awaitFirstFrame::<FQN>` | async | A `DEFERRED` initializer waiting for the first frame |
| `Startup::marker::<state>` | zero-length | `IN_PROGRESS`, `COMPLETED`, `ERROR`, `cancel` (may repeat), `deferredSignal\|deferredTimeout::<FQN>`, `priorityInversion::<FQN>`, `strictModeConcurrency` |

Counters (API 29+): `Startup::activeAsync` (async `create()` bodies running), `Startup::permitsWaiting`,
`Startup::permitsAvailable` (concurrency cap) and `Startup::busSize` (metrics currently held for replay).

**View them:** record a system trace and open it in [Perfetto](https://ui.perfetto.dev), the Android Studio
profiler, or capture one from the command line with `android profiler systemtrace`, then search for `Startup::`.

**Measure them:** Macrobenchmark can read the slices with `TraceSectionMetric("Startup::%")`, which matches
both sync and async slices. Counters are not readable by `TraceSectionMetric` (slices only); check them in
Perfetto. With `TraceSectionMetric.Mode.Sum` the wildcard double-counts nested and overlapping slices
(a node contains its `create`, `awaitAll` overlaps), so use it only as a presence check and assert on
specific section names or `Mode.Count`.

> **Note on androidx.tracing 2:** it was evaluated and is **not integrated**; `android.os.Trace` remains the
> only tracer and `:core` has no `androidx.tracing` dependency (CI guards the published POM). Without
> `tracing-wire` in the app, tracing 2 emits nothing; with it, events go to a separate in-process Perfetto
> file instead of the system trace. In our test (API 35 emulator only) adding `tracing-wire` turned the
> Macrobenchmark cold start into a warm start; the cause is inferred, not isolated. It would also force
> `androidx.tracing`/`androidx.collection` upgrades for consumers that resolve older versions transitively
> (strict version pins were not tested). A pluggable tracer (separate artifact) may be considered in a future change.

### Metrics reference

Beyond the fields listed in [Metrics](#metrics), `metricsFlow` is bounded by
`AppStartupConfig.metricsReplayCapacity` (default `256`). When exceeded, the oldest metrics are dropped
first and emitters never suspend. The same bound applies to live collectors: there is no extra buffer, so a
collector lagging by more than the capacity loses the oldest metrics (`DROP_OLDEST`). The drop warning is
only logged when `debugLoggingEnabled` is `true`.

### Dispatcher instrumentation (debug only)

```kotlin
AppStartupInitializer.configure {
    dispatcherInstrumentationEnabled = BuildConfig.DEBUG
}

val snapshots = AppStartupInitializer.getInstance(context).dispatcherSnapshots()
// label -> DispatcherSnapshot(label, peakConcurrency, maxQueueDelayMs, totalDispatched)
```

`StartupMetric.dispatcherName` then reports a stable label derived from the dispatcher (for example
`Dispatchers.IO`). **Do not enable it in production:** wrapping `Dispatchers.IO` reduces its thread-pool
elasticity and adds one `withContext` hop. It is `false` by default, in which case dispatchers are used
unwrapped and `dispatcherSnapshots()` is empty.

### Benchmarking

The `:benchmark` module holds a Macrobenchmark (`StartupBenchmark`, cold start with `CompilationMode.None`
and `Partial`, measuring TTID/TTFD plus `Startup::` sections) and a `BaselineProfileGenerator`. It targets the
`benchmarkRelease` build type added to `:app` by the Baseline Profile plugin and needs a connected device:

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
```

On an emulator the benchmark refuses to run unless you pass
`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`; emulator numbers
only prove the wiring, not performance. To generate the sample's Baseline Profile on a connected device:

```bash
./gradlew :app:generateReleaseBaselineProfile
```

A profile generated on an emulator is fine for the sample; regenerate it on a physical device for production.

### R8 / ProGuard

The bundled consumer rules (`core/consumer-rules.pro`) are required for initializers that are only reached
through another initializer's `dependencies()` and are not declared in the manifest, because they are
instantiated by name. Manifest-declared initializers are also kept by AGP's aapt2 rules, so they do not prove
the consumer rules work. CI builds the sample with R8 and runs `.github/scripts/check-r8-initializers.sh`,
which asserts that the library's `InitializationProvider`, the two manifest-declared sample initializers and
a dependency-only initializer survive with their no-arg constructors and are not renamed.

## Migration from AndroidX App Startup to App Startup Extension

If you are already using AndroidX App Startup, follow these steps to migrate to App Startup
Extension:

1. Modify `AndroidManifest.xml`:

   **Before:**

   ```xml
   <provider android:name="androidx.startup.InitializationProvider"
       android:authorities="${applicationId}.androidx-startup" 
       android:exported="false" 
       tools:node="merge" >
      <!-- This entry makes ExampleLoggerInitializer discoverable. -->
   </provider>
   ```

   **After:**

   ```xml
   <provider android:name="io.github.santimattius.android.startup.InitializationProvider"
       android:authorities="${applicationId}.app-startup-extension" 
       android:exported="false" 
       tools:node="merge" >
      <!-- This entry makes ExampleLoggerInitializer discoverable. -->
   </provider>
   ```

2. Change the `meta-data` value from `androidx.startup` to `sync-initializer`.
    ```xml
           <provider
            android:name="io.github.santimattius.android.startup.InitializationProvider"
            android:authorities="${applicationId}.app-startup-extension"
            android:exported="false"
            tools:node="merge">
            <!-- This entry makes ExampleLoggerInitializer discoverable. -->
            <meta-data  android:name="com.santimattius.android.sample.migration.ExampleLoggerInitializer"
                android:value="sync-initializer" />
        </provider>
    ```
3. Modify the `Initializer` implementation:

   **Before:**

   ```kotlin
   import androidx.startup.Initializer

   class ExampleLoggerInitializer : Initializer<Unit> {
       override fun create(context: Context) {
           // Initialization code
       }

       override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
   }
   ```

   **After:**

   ```kotlin
   import io.github.santimattius.android.startup.initializer.StartupSyncInitializer

   class ExampleLoggerInitializer : StartupSyncInitializer<Unit> {
       override fun create(context: Context) {
           // Initialization code
       }

       override fun dependencies(): List<Class<out StartupSyncInitializer<*>>> = emptyList()
   }
   ```

## Dependency changes

The library no longer declares `androidx.appcompat:appcompat` and `com.google.android.material:material`
(the library code never used them), so they are no longer transitive dependencies of your app. If your app
relied on this library pulling them in, declare them yourself.

## Contributions

Contributions are welcome! To contribute to this library, follow these steps:

1. Fork the repository.
2. Create a new branch for your contribution (`git checkout -b feature/new-feature`).
3. Implement your changes following the style guides and coding conventions.
4. Commit your changes with a clear description (`git commit -am 'Add new feature'`).
5. Push your changes to your repository (`git push origin feature/new-feature`).
6. Create a pull request on GitHub, describing your changes.

## Contact

If you have questions, issues, or suggestions about this library, feel free
to [open an issue](https://github.com/santimattius/android-app-startup-extension/issues) on GitHub. We are here to
help!

