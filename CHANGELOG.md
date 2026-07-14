# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

## [2.2.0] — 2026-07-14

### Added

#### Startup priority staggering (defer past the first frame)

- `StartupPriority` — new public enum (`CRITICAL`, `NORMAL`, `DEFERRED`) whose ordinal encodes
  eagerness (minimum ordinal = most eager). `DEFERRED` is the only value that changes scheduling in
  this release. `CRITICAL` is reserved for a future strict scheduler and currently behaves
  **identically to `NORMAL`** (it partitions as eager, does not bypass the concurrency cap, and is
  not ordered ahead of `NORMAL`).
- `StartupAsyncInitializer.priority(): StartupPriority` — additive default method returning
  `StartupPriority.NORMAL`. Same source- and binary-compatible pattern as the `dispatcher()`
  sentinel, so existing initializers keep their current behaviour with zero changes. Override to
  `DEFERRED` for work safe to run after the first frame (analytics warmers, prefetchers) so it never
  competes with UI-critical startup.
- `FirstFrameSignal` — new public seam (`suspend fun await()`) that resolves when the first frame
  has been drawn. `DEFERRED` roots suspend on it before their `create()` runs. Core scheduling
  depends only on this interface and never hardcodes `Choreographer`/`Activity` detection, so a
  `CompletableDeferred`-backed fake drives deferred scheduling deterministically in tests.
- Internal Android default first-frame signal, resolved lazily by the scheduler (only when a
  `DEFERRED` root exists) so the static config never holds a `Context`. It completes on the first
  activity's first draw and unregisters its lifecycle callback immediately (no `Activity` retained).
  In headless/no-`Application` processes it never self-completes; the timeout fallback flushes
  deferred work instead.
- `AppStartupConfig.firstFrameSignal: FirstFrameSignal?` — optional injectable signal (default
  `null` = use the internal Android default). Inject a fake to drive deferred scheduling under test.
- `AppStartupConfig.deferredStartupTimeoutMs: Long` — upper bound (default `5_000L`) the deferred
  gate waits for the first frame before flushing `DEFERRED` work anyway, guaranteeing headless/no-UI
  processes still run their deferred initializers. A value `<= 0` makes the gate return immediately
  (`withTimeoutOrNull(0)`) — a documented escape hatch, not a bug.

### Changed

- `asyncDiscoverAndInitialize` now partitions the roots it launches by **effective** priority: eager
  (`NORMAL`/`CRITICAL`) roots launch immediately on the critical path via the unchanged
  `launchStartJob` → `doAsyncInitialize` path, while `DEFERRED` roots launch via a separate gated
  coroutine that suspends on `withTimeoutOrNull(deferredStartupTimeoutMs) { firstFrameSignal.await() }`
  before calling the **same untouched** `doAsyncInitialize`. The `doAsyncInitialize` hot path is
  byte-for-byte unchanged, preserving deps-resolved-before-acquire ordering (no nested permits, no
  deadlock at cap `1`), the global `Semaphore` cap, and dispatcher precedence. The deferred gate
  holds **no** concurrency permit while suspended on the signal.
- `awaitAllStartJobs()` (and the `timeoutMs` overload) **excludes** `DEFERRED` jobs: they are
  tracked in a separate engine list, so the critical-path await returns without waiting for
  deferred work — it cannot block or deadlock at cap `1` or under `runBlocking`.
- Priority-inversion handling: when an eager (`NORMAL`/`CRITICAL`) initializer depends — directly or
  transitively — on a `DEFERRED` one, the library clamps the `DEFERRED` dependency's **effective**
  priority up to its most-eager dependent (so it launches eagerly, not after the frame) and logs a
  single warning per startup. It never rejects a valid graph at validation. The warning is routed
  through `StartupExtensionLogger`, so it is only visible when `debugLoggingEnabled` is on.
- Initializers with no `priority()` override behave **identically to 2.1.0** — this change is fully
  additive and backward-compatible.

---

## [2.1.0] — 2026-07-04

### Added

#### Coroutine storm mitigation (observe, then bound)

- `AppStartupConfig.maxConcurrentAsyncInitializers: Int?` — optional cap on how many async
  `create()` bodies may run concurrently. Defaults to `null` (unbounded — unchanged behaviour).
  Backed by a `kotlinx.coroutines.sync.Semaphore` acquired around each `create()`, applied
  globally and independently of `AsyncInitializerStrategy`. Values `<= 0` are normalised to `null`
  (unbounded) rather than deadlocking. Dependencies are always resolved *before* a permit is
  acquired, so a parent never holds a permit while awaiting a child — no deadlock even at cap `1`.
- `AppStartupConfig.defaultAsyncDispatcher: CoroutineDispatcher` — library-wide default dispatcher
  for async initializers that do not override `dispatcher()`. Defaults to `Dispatchers.Default`.
- `AppStartupConfig.strictModeConcurrencyThreshold: Int` — concurrency level above which strict
  mode logs a warning. Defaults to `Runtime.getRuntime().availableProcessors()`.
- Strict-mode concurrency warning — when `strictModeCheckEnabled` is on and the observed
  `concurrentActiveCount` exceeds `strictModeConcurrencyThreshold`, the library logs a single
  warning per startup (reporting the peak, threshold, dispatcher, and remediation). Silent when the
  flag is off or concurrency stays within the threshold.

#### Observability

- `StartupMetric` gained four async execution observability fields:
  - `dispatcherName: String` — the dispatcher `create()` actually ran on (empty for sync).
  - `threadName: String` — the thread `create()` actually ran on (empty for sync).
  - `concurrentActiveCount: Int` — snapshot of how many async initializers were executing
    `create()` when this one started (always `0` for sync).
  - `queueDelayMs: Long` — time from becoming ready to run to actually starting (concurrency-cap
    wait + dispatcher scheduling latency); the primary "coroutine storm" signal (always `0` for
    sync).

### Changed

- `StartupAsyncInitializer.dispatcher()` return type changed from `CoroutineDispatcher` to the
  nullable sentinel `CoroutineDispatcher?` (`null` = "use the library default"). This is
  **source-compatible** for existing overrides (a non-null `CoroutineDispatcher` return type
  validly overrides a `CoroutineDispatcher?` one via covariance) and **binary-compatible** (Kotlin
  nullability is an annotation; the JVM descriptor is unchanged). Behavioural change: an
  unoverridden initializer now honours `AppStartupConfig.defaultAsyncDispatcher` instead of always
  using `Dispatchers.Default`.
- `StartupMetric` gained four trailing fields with defaults (`dispatcherName`, `threadName`,
  `concurrentActiveCount`, `queueDelayMs`), appended after `wasCancelled` so `component1..5` and
  the existing getters stay ABI-stable. Because `StartupMetric` is library-emitted telemetry that
  consumers *read* (getters + destructuring), this is treated as a MINOR-style additive change.
  **Narrow binary-compatibility caveat:** the only affected ABI surface is the primary constructor
  / `copy()` descriptor — recompilation is required *only* if your code constructs or copies a
  `StartupMetric` (an anti-pattern for emitted telemetry). Adopting `binary-compatibility-validator`
  is recommended so future changes to this DTO are caught deliberately.

---

## [2.0.0] — 2026-05-05

### Added

#### Configuration DSL

- `AppStartupConfig` — immutable configuration snapshot produced by `AppStartupConfig.Builder`.
  Holds all runtime options in one place instead of scattered static calls.
- `AppStartupInitializer.configure { }` — single DSL entry point that replaces all previous static
  configuration methods. Call before `InitializationProvider` runs (e.g. in
  `Application.attachBaseContext`):
  ```kotlin
  AppStartupInitializer.configure {
      debugLoggingEnabled      = BuildConfig.DEBUG
      strictModeCheckEnabled   = BuildConfig.DEBUG
      syncOrderingStrategy     = SyncOrderingStrategy.Topological
      asyncInitializerStrategy = AsyncInitializerStrategy.Validated
  }
  ```
- `SyncOrderingStrategy` — sealed class controlling how sync initializers are ordered:
  - `Lazy` *(default)*: existing DFS recursive resolution; cycle detected mid-initialization.
  - `Topological`: Kahn's algorithm builds and validates the full dependency graph **before any
    `create()` is called**. Cycles are reported upfront and the exception message names every
    node participating in the cycle. Eliminates redundant `doInitialize` calls for components
    already resolved as transitive dependencies.
- `AsyncInitializerStrategy` — sealed class controlling how async initializers are launched:
  - `Concurrent` *(default)*: existing behaviour — one coroutine per discovered initializer,
    cycle detected lazily inside each coroutine's DFS chain.
  - `Validated`: builds the async-to-async dependency graph (via `dependencies()` only —
    `syncDependencies()` are excluded because all manifest-declared sync initializers have already
    completed on the main thread by the time async initializers start) before launching any
    coroutine. Cycles are detected upfront. Only **root** nodes — those no other async initializer
    declares as a dependency — receive an explicit coroutine; their transitive dependencies are
    resolved recursively inside that coroutine, reducing total coroutine launches for chained
    initializer graphs (e.g. a chain of N initializers launches 1 coroutine instead of N).

#### API

- `awaitAllStartJobs(timeoutMs: Long)` — new overload that throws `TimeoutCancellationException` if
  all jobs do not finish within the given milliseconds. The underlying jobs continue running
  unaffected; only the wait is cancelled.
- `startupState: StateFlow<StartupState>` — hot extension property on `AppStartupInitializer` that
  exposes the current startup lifecycle. Transitions: `IDLE → IN_PROGRESS → COMPLETED` on success,
  or `IDLE → IN_PROGRESS → ERROR` when a job throws.
- `StartupState` — enum with four states: `IDLE`, `IN_PROGRESS`, `COMPLETED`, `ERROR`.
- `StartupMetric` — data class capturing per-initializer timing (`durationMs`), type (`isAsync`),
  and outcome (`success`, `wasCancelled`).
- `AppStartupInitializer.metricsFlow: SharedFlow<StartupMetric>` — hot SharedFlow that emits after
  each initializer completes. All values are replayed (`replay = Int.MAX_VALUE`), so late collectors
  still receive the full startup sequence. Replaces the former `metricsListener` callback.
- `StartupExtensionException` — typed exception wrapping any error that occurs during
  initialization, propagated from both sync and async initializers.

#### `StartupAsyncInitializer`

- `syncDependencies(): List<Class<out StartupSyncInitializer<*>>>` — declares sync initializers
  that must complete before this async initializer's `create()` is invoked. Each dependency is
  initialized at most once across all async initializers that share it.
- `dispatcher(): CoroutineDispatcher` — overridable method to pin `create()` to a specific thread
  pool. Defaults to `Dispatchers.Default`. Return `Dispatchers.IO` for disk or network work.
- `retainAfterStartup(): Boolean` — overridable on both `StartupSyncInitializer` and
  `StartupAsyncInitializer`. Return `false` for side-effect-only initializers (migration runners,
  cache warmers, etc.) to have the library drop the result from its registry immediately after
  `create()` finishes, allowing GC to collect the object. Defaults to `true`.

#### `AppStartupInitializer`

- `isEagerlySyncInitialized(component)` — returns `true` if the given sync initializer was
  discovered and run automatically during startup.
- `isEagerlyAsyncInitialized(component)` — returns `true` if the given async initializer was
  discovered and launched automatically during startup.

### Changed

- `AppStartupInitializer.enableStrictModeCheck(Boolean)` and `enableDebugLogging(Boolean)` removed.
  Both options are now set via `configure { strictModeCheckEnabled = …; debugLoggingEnabled = … }`.
- `isAllStartedJobsDone()` now delegates to `areAllStartJobsDone()` inside the coroutines engine
  instead of accessing `startJobs` directly.
- Async initialization now uses a `Mutex` per-instance (rather than companion object level) to
  prevent double-initialization under concurrent callers.
- Discovery sets (`syncDiscovered`, `asyncDiscovered`) are backed by `CopyOnWriteArraySet` for
  safe concurrent reads during initialization.
- Each async initializer gets its own `initializing` set for cycle detection, preventing data races
  between concurrently running coroutines.
- `CancellationException` is now re-thrown as-is in async initialization paths, ensuring coroutine
  cancellation propagates correctly instead of being wrapped in `StartupExtensionException`.
- System tracing sections now use a `Startup::` prefix (e.g. `Startup::MyInitializer`) for easier
  identification in profiling tools.

### Fixed

- Instantiating an abstract class or interface as an initializer now fails immediately with a clear
  `StartupExtensionException` message instead of producing a cryptic `InstantiationException`.
- Duration measurement switched from `System.currentTimeMillis()` to `System.nanoTime()` for
  monotonic, wall-clock-independent timing. `durationMs` in `StartupMetric` is now accurate even
  when the system clock is adjusted during startup.
- `awaitAllStartJobs(timeoutMs)` now cancels all active jobs on timeout. Previously, jobs continued
  running as zombies after the wait expired.
- Class discovery now uses `Class.forName(name, false, context.classLoader)` — the `false` flag
  prevents unwanted static initialization during the discovery scan, and the explicit classloader
  ensures correct resolution in multi-classloader environments.
- `StartupMetric.wasCancelled` correctly distinguishes structured cancellation from real failures:
  when `true`, `success` is `false` but the event must not be reported as an error to alerting
  systems.

### Build

- Bundled R8/ProGuard consumer rules (`consumer-rules.pro`). Concrete implementations of
  `StartupSyncInitializer` and `StartupAsyncInitializer` — as well as `InitializationProvider` —
  are kept automatically. No manual keep rules required in consuming apps.

---

## [1.1.0] — 2026-04-13

### Added

- `AppStartupCoroutinesEngine` exposes `startJobs: List<Deferred<*>>` for external inspection of
  running jobs.
- `cancel()` method on `AppStartupCoroutinesEngine` to cancel the supervisor job and all running
  startup tasks.

### Changed

- Default `CoroutineDispatcher` is now `Dispatchers.Default` (was unset / inherited).
- `startJobs` backing list migrated from `ArrayList` to `CopyOnWriteArrayList` for thread safety.
- `awaitAllStartJobs()` clears the jobs list in a `finally` block, ensuring cleanup even on failure.

### Build

- Gradle wrapper bumped to 9.4.1.
- Updated AGP, Kotlin, and `mavenPublish` plugin versions.
- Target SDK bumped to 36.

---

## [1.0.0] — 2025-03-22

### Added

- `StartupSyncInitializer<T>` — interface for synchronous component initialization on the main
  thread.
- `StartupAsyncInitializer<T>` — interface for non-blocking, coroutine-based component
  initialization.
- `AppStartupInitializer` — central class for automatic discovery and initialization of components
  declared in `AndroidManifest.xml` via `meta-data` tags (`sync-initializer` /
  `async-initializer`).
- `InitializationProvider` — `ContentProvider` that triggers automatic discovery and initialization
  at app startup.
- `awaitAllStartJobs()` — suspends until all async initialization jobs complete.
- `onAppStartupLaunched { }` — executes a block only after all startup jobs finish.
- `isAllStartedJobsDone()` — non-suspending check; returns `true` when no jobs are active.
- Manual initialization support via `AppStartupInitializer.getInstance(context).doInitialize(...)`.
- Dependency ordering between initializers of the same type via `dependencies()`.
- Cycle detection in the dependency graph; throws `IllegalArgumentException` on circular
  dependencies.
- Published to Maven Central as
  `io.github.santimattius.android:app-startup-extension`.

[Unreleased]: https://github.com/santimattius/android-app-startup-extension/compare/2.2.0...HEAD
[2.2.0]: https://github.com/santimattius/android-app-startup-extension/compare/2.1.0...2.2.0
[2.1.0]: https://github.com/santimattius/android-app-startup-extension/compare/2.0.0...2.1.0
[2.0.0]: https://github.com/santimattius/android-app-startup-extension/compare/1.1.0...2.0.0
[1.1.0]: https://github.com/santimattius/android-app-startup-extension/compare/1.0.0...1.1.0
[1.0.0]: https://github.com/santimattius/android-app-startup-extension/releases/tag/1.0.0
