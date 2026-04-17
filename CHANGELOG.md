# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased] — 2.0.0

### Added

#### API

- `awaitAllStartJobs(timeoutMs: Long)` — new overload that throws `TimeoutCancellationException` if
  all jobs do not finish within the given milliseconds. The underlying jobs continue running
  unaffected; only the wait is cancelled.
- `startupState: StateFlow<StartupState>` — hot extension property on `AppStartupInitializer` that
  exposes the current startup lifecycle. Transitions: `IDLE → IN_PROGRESS → COMPLETED` on success,
  or `IDLE → IN_PROGRESS → ERROR` when a job throws.
- `StartupState` — enum with four states: `IDLE`, `IN_PROGRESS`, `COMPLETED`, `ERROR`.
- `StartupMetric` — data class capturing per-initializer timing (`durationMs`), type (`isAsync`),
  and outcome (`success`).
- `StartupMetricsListener` — functional interface invoked once per initializer after its `create()`
  completes, whether it succeeds or throws. Register via `AppStartupInitializer.metricsListener`.
- `AppStartupInitializer.enableDebugLogging(enabled: Boolean)` — static method to toggle library
  log output at runtime. Disabled by default. Call before `InitializationProvider` runs (e.g. in
  `Application.attachBaseContext`).
- `StartupExtensionException` — typed exception wrapping any error that occurs during
  initialization, propagated from both sync and async initializers.

#### `StartupAsyncInitializer`

- `syncDependencies(): List<Class<out StartupSyncInitializer<*>>>` — declares sync initializers
  that must complete before this async initializer's `create()` is invoked. Each dependency is
  initialized at most once across all async initializers that share it.
- `dispatcher(): CoroutineDispatcher` — overridable method to pin `create()` to a specific thread
  pool. Defaults to `Dispatchers.Default`. Return `Dispatchers.IO` for disk or network work.

#### `AppStartupInitializer`

- `isEagerlySyncInitialized(component)` — returns `true` if the given sync initializer was
  discovered and run automatically during startup.
- `isEagerlyAsyncInitialized(component)` — returns `true` if the given async initializer was
  discovered and launched automatically during startup.

### Changed

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

[Unreleased]: https://github.com/santimattius/android-app-startup-extension/compare/1.1.0...HEAD
[1.1.0]: https://github.com/santimattius/android-app-startup-extension/compare/1.0.0...1.1.0
[1.0.0]: https://github.com/santimattius/android-app-startup-extension/releases/tag/1.0.0
