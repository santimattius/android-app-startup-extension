![architecture - Startup Extension Initializer(1)](https://github.com/user-attachments/assets/9d44a786-7638-44de-952a-60059984abda)

[![Latest Release](https://maven-badges.sml.io/sonatype-central/io.github.santimattius.android/app-startup-extension/badge.svg?subject=Latest%20Release&color=blue)](https://maven-badges.sml.io/sonatype-central/io.github.santimattius.android/app-startup-extension/)
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
- **Metrics Flow:** Per-initializer timing, outcome, and concurrency/dispatcher data exposed as a hot `SharedFlow` — late collectors receive the full replay of all startup metrics.
- **Coroutine Storm Mitigation:** Optional cap on concurrent async `create()` executions (`maxConcurrentAsyncInitializers`) backed by a deadlock-safe `Semaphore`, plus a library-wide default dispatcher (`defaultAsyncDispatcher`) and a strict-mode warning when concurrency exceeds a configurable threshold.
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
(success or failure). All values are **replayed** — a collector that joins after startup finishes
will still receive the full set of metrics from the startup sequence.

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

