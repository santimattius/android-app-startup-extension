# Performance Analysis — `core/`

Analysis of performance problems, memory leaks, ANR risk and best practices in the `core/` code.

> **Status note.** This analysis was originally written against the 2.x code. Each finding below keeps its
> original description and now carries a **Status (3.0)** line, verified against `core/src/main/kotlin`.
> Line numbers from the original snapshot were removed because they no longer match; file names are current.

---

## Index

1. [Critical — ANR risk](#1-critical--anr-risk)
2. [Memory leaks](#2-memory-leaks)
3. [Bugs and correctness problems](#3-bugs-and-correctness-problems)
4. [Best practices](#4-best-practices)
5. [Prioritized summary](#5-prioritized-summary)

---

## 1. Critical — ANR risk

### 1.1 Sync initializers run on the Main Thread without a safeguard

**File:** `InitializationProvider.kt` → `AppStartupInitializer.kt` / `SyncRunner.kt`

`InitializationProvider.onCreate()` runs on the Main Thread (it is a `ContentProvider`). It calls `discoverAndInitialize()` directly, which runs the whole sync initialization chain synchronously. If any `StartupSyncInitializer.create()` performs I/O, accesses disk, or takes more than 5 seconds → **guaranteed ANR**.

The interface documents that `create()` must stay short, but nothing enforces it. There is no timeout and no detection of heavy work.

```kotlin
// InitializationProvider.kt
AppStartupInitializer.getInstance(context).discoverAndInitialize(javaClass)
// provider-driven startup runs entirely on the main thread
```

**Recommendation:** Add a `StrictMode.ThreadPolicy` check in debug builds to detect accidental I/O inside sync initializers. Consider documenting explicitly that `create()` must not perform blocking operations.

**Status (3.0):** Partially addressed. Sync initializers run on the *requesting* thread; provider-driven startup runs on the main thread because Android calls `ContentProvider.onCreate()` there, while on-demand calls (`initializeComponent` / `doInitialize`) run on the calling thread. There is still no timeout. The StrictMode recommendation is implemented: with `configure { strictModeCheckEnabled = true }`, each sync `create()` runs under a `StrictMode.ThreadPolicy` (`detectAll().penaltyLog()`) that is restored afterwards.

---

### 1.2 `getProviderInfo()` is a blocking IPC call on the Main Thread

**File:** `AppStartupInitializer.kt` (`discoverAndInitialize`)

```kotlin
val providerInfo = applicationContext.packageManager
    .getProviderInfo(provider, GET_META_DATA)
```

`getProviderInfo()` makes a binder call to the `PackageManagerService`. On devices with heavy IPC traffic at startup (many apps running, low memory), this call can block for tens or hundreds of milliseconds. It is an invisible latency point for whoever uses the library.

**Status (3.0):** Still applies. The call runs once per process on the provider path and is wrapped in a `Startup::getProviderInfo` trace section so it is visible in Perfetto.

---

## 2. Memory leaks

### 2.1 Metrics consumers can capture an Activity in a singleton

**File:** `AppStartupInitializer.kt`

In 2.x this finding referred to a `metricsListener` property of type `StartupMetricsListener`, which a caller could set with a lambda capturing an `Activity`, `Fragment` or `ViewModel`. That API no longer exists. In 3.0 metrics are exposed only through `AppStartupInitializer.metricsFlow`, a `SharedFlow<StartupMetric>`.

`AppStartupInitializer` is still a process-lifetime singleton (`sInstance`), so the same risk exists in a different form: a collector that captures an `Activity`, `Fragment` or `ViewModel` and runs in a long-lived scope keeps that object alive.

```kotlin
// Incorrect usage — Activity retained by a collector in a process-lifetime scope
appScope.launch {
    AppStartupInitializer.getInstance(this@MainActivity).metricsFlow.collect { metric ->
        this@MainActivity.updateUI(metric) // Activity captured by a long-lived collector
    }
}
```

**Recommendation:** Collect `metricsFlow` from a lifecycle-aware scope (for example `lifecycleScope` with `repeatOnLifecycle`), so the collection is cancelled together with the screen.

**Status (3.0):** `metricsListener` / `StartupMetricsListener` were removed. `metricsFlow` is bounded: late collectors get the last `metricsReplayCapacity` metrics (default 256), and `StartupMetric` carries only strings and numbers (no `Throwable`), so the replay cache itself does not retain heavy objects. See [Observability](observability.md).

---

### 2.2 The `initialized` map retains all startup objects indefinitely

**File:** `AppStartupInitializer.kt`

```kotlin
private val initialized = ConcurrentHashMap<Class<*>, Any>()
```

This map retains the instances of all objects created during startup for the whole life of the app. That makes sense for `create()` results that are needed at runtime. But if an initializer produces a factory or helper that is only used during initialization, it stays retained unnecessarily.

It is not a bug, but it is a trade-off that library users need to know about and take into account when designing their initializers.

**Status (3.0):** Unchanged by design, with an opt-out: override `retainAfterStartup()` to return `false` for results that are only needed during startup. Note that a transient initializer requested again later re-executes `create()`.

---

## 3. Bugs and correctness problems

### 3.1 `CancellationException` reported as `success = false` in metrics

**File:** `AppStartupInitializer.kt` (async path, now in `AsyncRunner.kt`)

```kotlin
var success = false
try {
    val result = withContext(initializerDispatcher) {
        asyncInitializer.create(applicationContext)
    }
    success = true
    result
} finally {
    metricsListener?.onInitializerCompleted(
        StartupMetric(..., success = success) // success = false if it was cancelled
    )
}
```

If the scope is cancelled, `success` stays `false` and it is reported as an initialization failure. A `CancellationException` is not a failure, it is structured cancellation. This pollutes any observability or alerting system that uses the `success` field.

**Recommendation:** Add a `wasCancelled: Boolean` field to `StartupMetric`, or do not report when the cause is a `CancellationException`.

**Status (3.0):** Resolved. `StartupMetric` has a `wasCancelled` field. On cancellation the metric is still published, with `success = false` and `wasCancelled = true`, and `errorType` / `errorMessage` stay empty, so consumers can tell a cancellation from a failure.

---

### 3.2 Orphaned coroutines after a timeout in `awaitAllStartJobs(timeoutMs)`

**File:** `AppStartupCoroutinesEngine.kt`

```kotlin
suspend fun awaitAllStartJobs(timeoutMs: Long) {
    try {
        withTimeout(timeoutMs) { _startJobs.awaitAll() }
        _startupState.value = StartupState.COMPLETED
    } catch (e: CancellationException) {
        throw e // TimeoutCancellationException takes this path
    } finally {
        _startJobs.clear() // clears the list but does NOT cancel the Deferreds
    }
}
```

`TimeoutCancellationException` is a subclass of `CancellationException`. It is rethrown, the `finally` clears the list, but the `Deferred`s that were running keep executing in the `SupervisorJob`. The reference to them is lost and there is no way to cancel them. They are zombie coroutines consuming CPU and I/O without control.

The `cancel()` method on `AppStartupCoroutinesEngine` exists but is not invoked from anywhere in the code.

**Recommendation:** In the `finally` block of the timeout version, explicitly cancel the jobs that did not complete, or call `supervisorJob.cancel()` and then recreate it if reuse is required.

**Status (3.0):** Resolved for the timeout path. The `CancellationException` branch now cancels every start job that is still active (`_startJobs.filter { it.isActive }.forEach { it.cancel() }`) before rethrowing. The engine's `cancel()` method still has no callers in `core/src/main/kotlin`.

---

### 3.3 `Class.forName()` without an explicit ClassLoader

**File:** `AppStartupInitializer.kt` (now in `SyncRunner.kt` and `AsyncRunner.kt`)

```kotlin
Class.forName(key)
```

`Class.forName(String)` uses the caller's classloader. In Dynamic Feature Modules, Play Asset Delivery, or test instrumentation environments, the classloader may not see the classes of the feature module, resulting in a `ClassNotFoundException` at runtime.

**Recommendation:**

```kotlin
Class.forName(key, false, applicationContext.classLoader)
```

**Status (3.0):** Resolved. Both runners now call `Class.forName(key, false, applicationContext.classLoader)`.

---

### 3.4 `System.currentTimeMillis()` to measure initialization duration

**File:** `AppStartupInitializer.kt` (now in `SyncRunner.kt` and `AsyncRunner.kt`)

```kotlin
val startMs = System.currentTimeMillis()
// ...
durationMs = System.currentTimeMillis() - startMs,
```

`System.currentTimeMillis()` measures wall-clock time and can jump backwards if the system syncs its time via NTP. To measure the duration of operations, the correct practice on Android is to use monotonic time:

```kotlin
val startMs = SystemClock.elapsedRealtime()
// or for greater precision:
val startNs = System.nanoTime()
```

**Status (3.0):** Resolved. Durations are computed from `System.nanoTime()`; the timestamps reported in `StartupMetric` come from `SystemClock.uptimeMillis()`.

---

## 4. Best practices

### 4.1 `CopyOnWriteArrayList` on a write hot path

**File:** `AppStartupCoroutinesEngine.kt`

```kotlin
private val _startJobs = CopyOnWriteArrayList<Deferred<*>>()
```

`CopyOnWriteArrayList` is optimized for frequent reads and rare writes. During startup, `launchStartJob` is called N times (once per async initializer), and every `add()` copies the whole array. This generates unnecessary allocations at the moment of highest memory pressure.

**Recommendation:** Use `Collections.synchronizedList(ArrayList())` or an `ArrayList` guarded with `synchronized`, which is more efficient for this usage pattern.

**Status (3.0):** Resolved. `_startJobs` is now `Collections.synchronizedList(ArrayList())`.

---

### 4.2 Threading of metric delivery is undocumented

**File:** `AppStartupInitializer.kt` (now in `SyncRunner.kt`, `AsyncRunner.kt` and `StartupMetricsBus.kt`)

In 2.x, `metricsListener?.onInitializerCompleted(...)` was invoked directly from the initializer's execution path:

- For sync initializers: the callback ran on the **Main Thread**.
- For async initializers: it ran on the initializer's dispatcher (`IO`, `Default`, or a custom one).

The listener had no guarantee about which thread it ran on, and this was not documented. An implementer that touched UI or a non-thread-safe object from the listener could crash silently or produce undefined behavior.

**Recommendation:** Document explicitly that `onInitializerCompleted` can be invoked from any thread, and that the implementer is responsible for dispatching to the correct thread if needed.

**Status (3.0):** Superseded. There is no listener callback anymore: metrics are published to `metricsFlow` with a non-suspending `tryEmit`, so emitters never block, and each `collect` lambda runs on the dispatcher of the coroutine that launched the collection. Sync metrics are produced on the thread that runs the sync initializer (the requesting thread, which is the main thread for provider-driven startup). See [Observability](observability.md).

---

### 4.3 Duplicate `withTimeout` import

**File:** `AppStartupCoroutinesEngine.kt`

```kotlin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeout // exact duplicate
```

Duplicate import. Cosmetic, but it indicates that an unused/duplicate imports pass was not run. It can be confusing in code reviews.

**Status (3.0):** Resolved. The import appears once.

---

### 4.4 The `applicationContext` null check is dead code

**File:** `InitializationProvider.kt`

```kotlin
val applicationContext = context.applicationContext
if (applicationContext != null) { ... }
```

`context.applicationContext` returns an `Application` instance. In the normal Android lifecycle, if `ContentProvider.onCreate()` is being invoked, `applicationContext` is never null. This guard is defensive code with no real value that adds reading noise.

**Status (3.0):** Resolved. `InitializationProvider.onCreate()` no longer null-checks `applicationContext`; it only fails fast with a `StartupExtensionException` if the provider `context` itself is null.

---

## 5. Prioritized summary

| Priority | Category | Problem | File | Status (3.0) |
|-----------|-----------|----------|---------|--------------|
| 🔴 Critical | ANR | Sync initializers on the Main Thread without a safeguard | `InitializationProvider.kt` | Partially addressed (opt-in StrictMode check; no timeout) |
| 🔴 Critical | ANR | `getProviderInfo()` is a blocking IPC call on the Main Thread | `AppStartupInitializer.kt` | Still applies (traced) |
| 🟠 Leak | Memory | Metrics consumer can retain an Activity in a singleton (formerly `metricsListener`) | `AppStartupInitializer.kt` | Reframed: `metricsFlow` collectors |
| 🟡 Bug | Correctness | Orphaned coroutines after a timeout | `AppStartupCoroutinesEngine.kt` | Resolved |
| 🟡 Bug | Correctness | `CancellationException` reported as a failure in metrics | `AsyncRunner.kt` | Resolved (`wasCancelled`) |
| 🟡 Bug | Correctness | `Class.forName()` without an explicit ClassLoader | `SyncRunner.kt`, `AsyncRunner.kt` | Resolved |
| 🟡 Bug | Correctness | `System.currentTimeMillis()` used to measure duration | `SyncRunner.kt`, `AsyncRunner.kt` | Resolved |
| 🔵 BP | Performance | `CopyOnWriteArrayList` on a write hot path | `AppStartupCoroutinesEngine.kt` | Resolved |
| 🔵 BP | API | Thread of the metrics callback not documented | `StartupMetricsBus.kt` | Superseded by `metricsFlow` |
| 🔵 BP | Cleanup | Duplicate `withTimeout` import | `AppStartupCoroutinesEngine.kt` | Resolved |
| 🔵 BP | Cleanup | Dead null check on `applicationContext` | `InitializationProvider.kt` | Resolved |
