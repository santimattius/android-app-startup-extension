# Priorities and deferred startup

Async initializers can declare when they should be launched. This page covers `StartupPriority`, the
first-frame gate used by `DEFERRED` initializers, and the related configuration.

## Priorities

Declare a priority by overriding `StartupAsyncInitializer.priority()`. Sync initializers have no priority.

| Value | Behavior |
|---|---|
| `StartupPriority.CRITICAL` | Reserved for a future strict scheduler. Currently behaves exactly like `NORMAL`: launched eagerly, subject to the concurrency cap, not ordered ahead of `NORMAL`. |
| `StartupPriority.NORMAL` (default) | Launched immediately at startup. |
| `StartupPriority.DEFERRED` | Launched only after the first-frame signal resolves, or after `deferredStartupTimeoutMs` elapses. |

```kotlin
class AnalyticsWarmupInitializer : StartupAsyncInitializer<Unit> {
    override fun priority() = StartupPriority.DEFERRED

    override suspend fun create(context: Context) {
        // Runs after the first frame (or after the timeout), never on the critical path.
    }
}
```

## The first-frame gate

For each `DEFERRED` root the library:

1. Suspends on the `FirstFrameSignal`, bounded by `withTimeoutOrNull(deferredStartupTimeoutMs)`. No
   concurrency permit is held while waiting.
2. Records a trace marker, `Startup::marker::deferredSignal::<FQN>` if the signal resolved or
   `Startup::marker::deferredTimeout::<FQN>` if the timeout won.
3. Runs the normal initialization (dependencies, permit, dispatcher, `create()`).

The default signal completes when the decor view of the first created activity invokes its draw listener.
It is created lazily and only when a `DEFERRED` root exists. In a process that never draws a frame (a
headless process), the default signal never completes, so the timeout is what releases the deferred work.

Setting `deferredStartupTimeoutMs` to `0` or a negative value makes the gate return immediately, so deferred
work is flushed without waiting for a frame.

### Custom signals

Implement `FirstFrameSignal` to tie deferral to something else, for example a specific screen becoming
interactive, and register it in `AppStartupConfig`:

```kotlin
class ScreenReadySignal(private val ready: CompletableDeferred<Unit>) : FirstFrameSignal {
    override suspend fun await() = ready.await()
}

AppStartupInitializer.configure {
    firstFrameSignal = ScreenReadySignal(readyDeferred)
    deferredStartupTimeoutMs = 3_000L
}
```

`await()` must be safe to call from several coroutines and must resume all of them once signalled. The
instance lives for the whole process, so it must not retain an `Activity` or `Fragment`.

## What deferred means for awaiting and state

- `awaitAllStartJobs()` and `onAppStartupLaunched { }` do not wait for `DEFERRED` jobs. They are tracked
  separately so the await cannot block on work that is intentionally suspended on the first frame.
- A failing `DEFERRED` job never crashes the process and never reaches `awaitAllStartJobs()`, because
  nobody awaits it.
- `startupState` moves out of `IDLE` when the first eager job launches. A startup that has only
  `DEFERRED` roots stays `IDLE`, and it is never moved to `COMPLETED` or `ERROR` by deferred work.

## Priority inversion

If an eager (`NORMAL` or `CRITICAL`) initializer depends, directly or transitively, on a `DEFERRED` one,
deferring the dependency would strand the dependent behind the first frame. The library clamps the
dependency's effective priority up to the most eager dependent and logs a single `Log.w` warning per
startup (tag `StartupLogger`, not gated by `debugLoggingEnabled`). A
`Startup::marker::priorityInversion::<FQN>` trace marker is also emitted.

`StartupMetric.priority` reports the declared priority, not the clamped one.

To avoid the warning, either make the dependent `DEFERRED` too or mark the dependency `NORMAL`.

## Which initializers are gated

The gate applies to launched roots by effective priority. Under `AsyncInitializerStrategy.Concurrent` every
discovered async initializer is a launched root. Under `Validated` only roots are launched, and a deferred
dependency of an eager root is resolved inside that root's coroutine.

## Next steps

- [Awaiting startup](awaiting-startup.md)
- [Configuration](configuration.md)
- [Architecture: async execution](../architecture.md#6-async-execution)
