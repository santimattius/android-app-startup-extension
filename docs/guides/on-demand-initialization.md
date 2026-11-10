# On-demand initialization

Besides manifest-driven startup, you can initialize and retrieve components yourself through
`AppStartupInitializer`. This page covers the entry points, their threading, and their locking behavior.

## Entry points

Obtain the singleton with `AppStartupInitializer.getInstance(context)`.

| Member | Signature | Notes |
|---|---|---|
| `initializeComponent` | `fun <T : Any> initializeComponent(component: Class<out StartupSyncInitializer<T>>): T` | Typed convenience; same as sync `doInitialize` |
| `doInitialize` (sync) | `fun <T> doInitialize(component: Class<out StartupSyncInitializer<*>>): T` | Blocks the calling thread |
| `doInitialize` (async) | `suspend fun <T> doInitialize(component: Class<out StartupAsyncInitializer<*>>): T` | Suspends until the component is ready |
| `isEagerlySyncInitialized` / `isEagerlyAsyncInitialized` | `fun ...(component: Class<...>): Boolean` | `true` if the class was discovered from manifest metadata at startup |

```kotlin
val startup = AppStartupInitializer.getInstance(context)

// Sync: runs create() on this thread and returns the (retained) result.
val config = startup.initializeComponent(ConfigInitializer::class.java)

// Async: call from a coroutine.
scope.launch {
    val db = startup.doInitialize<Database>(DatabaseInitializer::class.java)
}
```

If the component was already initialized (by startup or by an earlier request) and is retained, its result
is returned without running `create()` again.

## Threading

- The sync variants run `create()` on the **calling thread** and block it. In 2.x the call was dispatched to
  the main thread; in 3.0 it is not. See the [3.0 migration guide](../migration-3.0.md).
- The async variant initializes in the caller's coroutine context and then runs `create()` on the resolved
  dispatcher.

## Locking and de-duplication

- **Sync:** coordinated per component. Unrelated components can initialize concurrently on different
  threads. A second thread that requests a component whose `create()` is in flight waits and receives the
  same retained result. Cross-thread dependency cycles raise `StartupExtensionException` instead of
  deadlocking.
- **Async:** the on-demand `suspend doInitialize` takes a single `Mutex` for the whole call, so on-demand
  async calls are serialized with each other, even for unrelated components. Startup-driven async work does
  not use this mutex, and an already initialized component returns before the mutex is taken.

Sync initialization is per component, while on-demand async initialization is globally serialized. If
several unrelated async components are requested at once and latency matters, request them from startup
(manifest) or accept the serialization.

## Transient components

For components with `retainAfterStartup() == false`, the result is not kept after initialization, so every
request runs `create()` again. This is also the case for on-demand calls.

## Tracing

Public on-demand calls are wrapped in a `Startup::onDemand::<FQN>` section. Startup-driven work is not.
See [Observability](../observability.md#trace-sections).

## Next steps

- [Awaiting startup](awaiting-startup.md)
- [Troubleshooting](troubleshooting.md)
- [API reference: `AppStartupInitializer`](../api-reference.md#appstartupinitializer)
