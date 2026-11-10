# Awaiting startup

Async initializers are launched at process start and nothing waits for them by default. This page covers
the extension functions that let you wait for, observe and query startup.

All of them are defined in `AppStartupExtension.kt` as extensions of `AppStartupInitializer`, and they live in
the package `io.github.santimattius.android.startup`.

## `awaitAllStartJobs()`

Suspends until every non-deferred start job has finished.

```kotlin
val startup = AppStartupInitializer.getInstance(appContext)

scope.launch {
    startup.awaitAllStartJobs()
}
```

- If a job failed, the first failure is rethrown and `startupState` becomes `ERROR`.
- `DEFERRED` jobs are not awaited ([Priorities and deferred startup](priorities-and-deferred.md)).
- On success, `startupState` becomes `COMPLETED`.

## `awaitAllStartJobs(timeoutMs)`

Same as above, bounded by a timeout:

```kotlin
scope.launch {
    try {
        startup.awaitAllStartJobs(timeoutMs = 5_000)
    } catch (e: TimeoutCancellationException) {
        // startup took longer than 5 seconds
    }
}
```

On timeout (or any cancellation), the start jobs that are still active are **cancelled**, not just the
wait, and the exception is rethrown. Cancelled initializers publish a metric with `wasCancelled = true`.
Do not use a short timeout as a "check" if you want startup to keep running.

## `onAppStartupLaunched { }`

Runs `awaitAllStartJobs()` and then your block. A startup failure propagates to the caller.

```kotlin
scope.launch {
    startup.onAppStartupLaunched {
        Log.d("App", "all initialization tasks completed")
    }
}
```

## `isAllStartedJobsDone()`

A non-suspending check. It returns `true` when no tracked start job is active, whether the jobs completed,
failed or were cancelled. Deferred jobs are not tracked. It also returns `true` before any job is launched.

```kotlin
if (startup.isAllStartedJobsDone()) {
    showHome()
}
```

## `startupState`

A hot `StateFlow<StartupState>`.

```
IDLE -> IN_PROGRESS -> COMPLETED
                   \-> ERROR
```

| State | Meaning |
|---|---|
| `IDLE` | No start job has been launched |
| `IN_PROGRESS` | At least one eager start job has been launched |
| `COMPLETED` | An `awaitAllStartJobs` call finished without a failure |
| `ERROR` | An `awaitAllStartJobs` call observed a non-cancellation failure |

Important semantics, verified in `AppStartupCoroutinesEngine`:

- `COMPLETED` and `ERROR` are set **only when `awaitAllStartJobs` runs** (directly or through
  `onAppStartupLaunched`). Jobs finishing on their own do not change the state.
- `ERROR` is sticky: a later `COMPLETED` does not overwrite it.
- A cancelled await (including a timeout) does not change the state.
- A startup that has only `DEFERRED` roots never leaves `IDLE`.

```kotlin
scope.launch {
    startup.startupState.collect { state ->
        when (state) {
            StartupState.COMPLETED -> Log.d("App", "startup finished")
            StartupState.ERROR -> Log.e("App", "startup failed")
            else -> Unit
        }
    }
}
```

Because the state depends on an await call, make sure something awaits startup (for example the sample app
calls `onAppStartupLaunched` from `Application.onCreate`).

## Next steps

- [Priorities and deferred startup](priorities-and-deferred.md)
- [Observability](../observability.md)
- [API reference: extension functions](../api-reference.md#extension-functions-and-properties)
