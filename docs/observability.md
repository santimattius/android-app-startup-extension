# Observability

The library exposes what it does at startup in three ways: a metrics flow, system trace sections for
Perfetto and Macrobenchmark, and optional dispatcher snapshots. This page describes each one.

## Metrics flow

`AppStartupInitializer.metricsFlow` is a hot `SharedFlow<StartupMetric>`. One metric is published each time
an initializer execution finishes, whether it succeeded, failed or was cancelled.

```kotlin
lifecycleScope.launch {
    AppStartupInitializer.getInstance(applicationContext).metricsFlow.collect { m ->
        Log.d(
            "Startup",
            "${m.initializerFqn} took ${m.durationMs}ms " +
                "(async=${m.isAsync}, ok=${m.success}, cancelled=${m.wasCancelled}, " +
                "queueDelay=${m.queueDelayMs}ms, thread=${m.threadName})",
        )
    }
}
```

Behavior:

- **Bounded replay.** A collector that joins after startup receives the most recent `metricsReplayCapacity`
  metrics (default 256) in emission order, which is the full startup in practice.
- **Slow collectors lose metrics.** The same bound applies to live collectors. There is no extra buffer, so a
  collector that lags by more than the capacity loses the oldest metrics (`DROP_OLDEST`). Emitters never
  suspend.
- **Drop warning.** The warning about dropped metrics is only logged when `debugLoggingEnabled` is `true`.
- **Emission thread.** Metrics are emitted from the initializer's own execution context. Your `collect`
  lambda runs on the dispatcher of the coroutine that launched the collection.
- **No `Throwable`.** Only the error type and message strings are kept.

A synchronous snapshot is available with `metricsFlow.replayCache`.

### `StartupMetric` fields

The full definition is in the [API reference](api-reference.md#startupmetric). The fields that matter most
when investigating startup:

| Field | Use it to |
|---|---|
| `initializerFqn` | Identify the initializer; it matches the trace section names (`initializerName` is the simple name) |
| `durationMs` | See how long `create()` took |
| `isAsync`, `success`, `wasCancelled` | Split sync from async, failures from cancellations (a cancellation is not an error) |
| `errorType`, `errorMessage` | Report failures without keeping a stack trace |
| `dispatcherName`, `threadName` | See where an async body actually ran (empty for sync) |
| `concurrentActiveCount` | See how many async bodies were active when this one started |
| `queueDelayMs` | Detect a coroutine storm: time from ready-to-run to start (permit wait plus dispatcher scheduling) |
| `permitWaitMs` | Isolate the concurrency-cap wait; `queueDelayMs - permitWaitMs` approximates dispatcher delay |
| `startTimestampMs`, `endTimestampMs`, `processStartOffsetMs` | Place the initializer on the process timeline (`SystemClock.uptimeMillis()` clock; `0` when unknown) |
| `priority` | The **declared** priority, not the clamped effective one |

Sync metrics have `dispatcherName`, `threadName` empty and `concurrentActiveCount`, `queueDelayMs` and
`permitWaitMs` equal to `0`.

### Avoiding leaks

`metricsFlow` lives on a process-lifetime singleton. A collector that captures an `Activity` or `Fragment`
keeps it alive for as long as the collection runs. Collect from a lifecycle-aware scope such as
`lifecycleScope` or `repeatOnLifecycle`, and never store UI references in the collector. The same applies
to a custom `FirstFrameSignal`, which is held for the whole process.

## Trace sections

The library writes `android.os.Trace` sections, so they appear in any system trace. Names follow
`Startup::<phase>::<FQN>`, where `<FQN>` is the initializer's fully-qualified class name. Names are capped at
127 characters; when longer, the prefix is kept and the FQN is shortened from the left.

Sections are **sync** (thread slices) when the block cannot suspend and **async** (process-track slices,
API 29+) when it can. Below API 29 only the sync ones are emitted, and async slices and counters are no-ops.
Tracing never changes behavior and is skipped when disabled.

| Section | Kind | Meaning |
|---|---|---|
| `StartupExtension` | sync | Outer slice around provider-driven discovery and initialization. It has no `Startup::` prefix |
| `Startup::getProviderInfo` | sync | The blocking `PackageManager.getProviderInfo` IPC call made at startup |
| `Startup::<FQN>` | sync / async | Whole initializer node |
| `Startup::discover::sync`, `Startup::discover::async` | sync | Reading manifest metadata and finding initializers |
| `Startup::resolveGraph::topological` | sync | Kahn sort of sync initializers (`SyncOrderingStrategy.Topological`) |
| `Startup::resolveGraph::validateConcurrent` | sync | Up-front async graph validation including transitive dependencies (`AsyncInitializerStrategy.Concurrent`) |
| `Startup::resolveGraph::validateRoots` | sync | Manifest-declared async graph validation and root detection (`AsyncInitializerStrategy.Validated`) |
| `Startup::resolveGraph::effectivePriorities` | sync | Effective-priority resolution and priority-inversion clamp |
| `Startup::instantiate::<FQN>` | sync | Reflective construction of the initializer |
| `Startup::deps::<FQN>` | sync / async | Resolving the initializer's dependencies |
| `Startup::permitWait::<FQN>` | async | Waiting for a `maxConcurrentAsyncInitializers` permit |
| `Startup::create::<FQN>` | sync / async | The `create()` body |
| `Startup::lockWait::sync` | sync | Waiting for a sync component lock |
| `Startup::lockWait::async::<FQN>` | async | Waiting for the mutex that serializes on-demand async initialization |
| `Startup::awaitAll` | async | `awaitAllStartJobs()` waiting for the critical path |
| `Startup::onDemand::<FQN>` | sync / async | Public `initializeComponent` / `doInitialize` calls (startup-driven work is not wrapped) |
| `Startup::deferred::awaitFirstFrame::<FQN>` | async | A `DEFERRED` initializer waiting for the first frame |

**Markers** (zero-length slices): `Startup::marker::IN_PROGRESS`, `Startup::marker::COMPLETED` and
`Startup::marker::ERROR` (state transitions, emitted only on a real change); `Startup::marker::cancel`
(emitted on cancellation and also when an await catches a `CancellationException`, so it can appear more
than once); `Startup::marker::deferredSignal::<FQN>` and `Startup::marker::deferredTimeout::<FQN>`;
`Startup::marker::priorityInversion::<FQN>`; `Startup::marker::strictModeConcurrency`.

**Counters** (API 29+): `Startup::activeAsync` (async `create()` bodies running), `Startup::permitsWaiting`
and `Startup::permitsAvailable` (concurrency cap), and `Startup::busSize` (metrics currently held for replay).

### Viewing a trace

Record a system trace and open it in [Perfetto](https://ui.perfetto.dev) or the Android Studio profiler,
then search for `Startup::`.

### Measuring with Macrobenchmark

`TraceSectionMetric` matches section names with a SQL `LIKE` pattern, so the wildcard `Startup::%` matches
every library section except the outer `StartupExtension` slice. The sample's benchmark uses it:

```kotlin
@OptIn(ExperimentalMetricApi::class)
val sectionMetrics = listOf(
    TraceSectionMetric("Startup::%", TraceSectionMetric.Mode.Count, label = "sectionCount"),
    TraceSectionMetric("Startup::create::com.example.app.AnalyticsInitializer", label = "analyticsCreate"),
)
```

Notes:

- The wildcard matches both sync and async slices. Counters are not readable by `TraceSectionMetric`
  (slices only); check them in Perfetto.
- With `TraceSectionMetric.Mode.Sum` the wildcard double-counts nested and overlapping slices (a node
  contains its `create`, and `awaitAll` overlaps other work). Use it only as a presence check and assert on
  specific section names or on `Mode.Count`.
- Async slices end when the asynchronous work ends, so keep the benchmark running long enough. The sample's
  benchmark sleeps for the duration of its async initializer after the activity starts.

### Benchmarking the sample

The `:benchmark` module contains a Macrobenchmark (`StartupBenchmark`: cold start with
`CompilationMode.None` and `Partial`, measuring TTID/TTFD plus `Startup::` sections) and a
`BaselineProfileGenerator`. It targets the `benchmarkRelease` build type added to `:app` by the Baseline
Profile plugin and needs a connected device:

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
```

On an emulator the benchmark refuses to run unless you pass
`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`; emulator numbers
only prove the wiring, not performance. To generate the sample's Baseline Profile on a connected device:

```bash
./gradlew :app:generateReleaseBaselineProfile
```

A profile generated on an emulator is fine for the sample; regenerate it on a physical device for
production.

### Note on androidx.tracing 2

It was evaluated and is **not integrated**: `android.os.Trace` remains the only tracer and `:core` has no
`androidx.tracing` dependency (CI guards the published POM). Without `tracing-wire` in the app, tracing 2
emits nothing; with it, events go to a separate in-process Perfetto file instead of the system trace. In the
maintainers' test (API 35 emulator only), adding `tracing-wire` turned the Macrobenchmark cold start into a
warm start; the cause is inferred, not isolated. It would also force `androidx.tracing` and
`androidx.collection` upgrades for consumers that resolve older versions transitively (strict version pins
were not tested). A pluggable tracer (separate artifact) may be considered in a future change.

## Dispatcher snapshots

Dispatcher instrumentation is opt-in and for debugging or measurement only.

```kotlin
AppStartupInitializer.configure {
    dispatcherInstrumentationEnabled = BuildConfig.DEBUG
}

// later
val snapshots: Map<String, DispatcherSnapshot> =
    AppStartupInitializer.getInstance(context).dispatcherSnapshots()
```

Each `DispatcherSnapshot` has `label`, `peakConcurrency`, `maxQueueDelayMs` and `totalDispatched`. With
instrumentation on, `StartupMetric.dispatcherName` carries the same label, derived from the dispatcher's
`toString()` with identity hash, bracketed pool statistics and package prefix removed (for example
`ThreadPoolExecutor` or `Dispatchers.IO`). Distinct dispatchers with the same label get a `#n` suffix.

**Do not enable it in production.** Wrapping `Dispatchers.IO` reduces its thread-pool elasticity, and
`withContext` no longer takes the same-dispatcher fast path (one extra hop). With the flag off (default),
dispatchers are used unwrapped and `dispatcherSnapshots()` is empty.

## Debug logging

`debugLoggingEnabled = true` makes the library log discovery, initialization, dropped-metric and
strict-mode messages with the tag `StartupLogger`. Messages are built lazily, so nothing is allocated for
them while logging is off. The priority-inversion warning is the exception: it is always logged with
`Log.w`.

## Next steps

- [Configuration](guides/configuration.md)
- [Troubleshooting](guides/troubleshooting.md)
- [Architecture: metrics and tracing](architecture.md#9-metrics-and-tracing)
- [Coroutine storm](coroutine-storm.md)
