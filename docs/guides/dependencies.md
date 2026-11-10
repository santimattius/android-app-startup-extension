# Dependencies

Initializers can declare what must run before them. This page covers the declaration, the ordering
guarantees, and how cycles are reported.

## Declaring dependencies

| Initializer | Member | Lists |
|---|---|---|
| `StartupSyncInitializer` | `dependencies()` | Sync initializer classes |
| `StartupAsyncInitializer` | `dependencies()` | Async initializer classes |
| `StartupAsyncInitializer` | `syncDependencies()` | Sync initializer classes that must finish before this `create()` |

A sync initializer can only depend on other sync initializers. An async initializer can depend on both
kinds, which is what makes the model mixed.

```kotlin
class LoggerInitializer : StartupSyncInitializer<Unit> {
    override fun create(context: Context) { /* ... */ }
}

class ConfigInitializer : StartupSyncInitializer<Config> {
    override fun create(context: Context): Config = Config.load(context)
    override fun dependencies() = listOf(LoggerInitializer::class.java)
}

class AnalyticsInitializer : StartupAsyncInitializer<Analytics> {
    override fun syncDependencies() = listOf(ConfigInitializer::class.java)
    override fun dependencies() = listOf(NetworkInitializer::class.java)

    override suspend fun create(context: Context): Analytics = Analytics.start(context)
}
```

Initializers that are only reached through `dependencies()` or `syncDependencies()` do not need a manifest
entry. The consumer keep rules cover them ([Getting started](../getting-started.md#r8-and-proguard)).

## What is guaranteed

- Every dependency has finished before the dependent's `create()` is called.
- Each class is created at most once per startup; shared dependencies are de-duplicated.
- For an async initializer, `dependencies()` are resolved first (sequentially), then `syncDependencies()`,
  and only then is the concurrency permit requested and `create()` dispatched.
- `syncDependencies()` run in the current coroutine context. They are not posted to the main thread.
- A failing dependency makes its dependents fail as well, because their `create()` never runs.

## Ordering strategies

Ordering is controlled by two options in `AppStartupInitializer.configure`.

### Sync: `syncOrderingStrategy`

| Value | Behavior |
|---|---|
| `SyncOrderingStrategy.Lazy` (default) | Discovery order; each initializer initializes its pending dependencies depth-first. A cycle is found mid-initialization, so some `create()` calls may already have run. |
| `SyncOrderingStrategy.Topological` | Kahn's algorithm runs over the discovered set before any `create()`. A cycle is reported up front with every participating class name. |

### Async: `asyncInitializerStrategy`

| Value | Behavior |
|---|---|
| `AsyncInitializerStrategy.Concurrent` (default) | Every discovered async initializer gets its own coroutine. The graph, including transitive dependencies that are not in the manifest, is validated up front (trace `Startup::resolveGraph::validateConcurrent`). |
| `AsyncInitializerStrategy.Validated` | Only manifest-declared nodes are validated (`Startup::resolveGraph::validateRoots`) and only root initializers are launched. Their dependencies are resolved inside the root's coroutine, which reduces the number of coroutines. A root is an async initializer that no other discovered async initializer depends on. |

`syncDependencies()` are not part of the validated async graph in either strategy.

## Cycles

| Case | Result |
|---|---|
| Cycle among sync initializers with `Topological` | `StartupExtensionException("Cycle detected in sync initializers: [...]")` before any `create()` |
| Cycle among async initializers (both strategies) | `StartupExtensionException("Cycle detected in async initializers: [...]")` from the provider, before any coroutine is launched |
| Cycle reached at runtime (for example `Lazy` sync ordering, or via an on-demand call) | `Cannot initialize <FQN>. Cycle detected.` raised from the dependency chain |
| Two threads each own a sync initializer and wait for the other's | `StartupExtensionException("Cannot initialize <FQN>. Cross-thread cycle detected.")` |

During provider startup a failing sync initializer is fatal for the process, because the exception
propagates out of `ContentProvider.onCreate()`. Use `Topological` in debug builds to fail early with a
complete list of the classes in the cycle.

A thread that is blocked on something outside the library (for example a worker waiting for the main thread
while main waits for that worker's initializer) cannot be detected. See the
[3.0 migration guide](../migration-3.0.md#main-thread-only-initialization).

## Next steps

- [Priorities and deferred startup](priorities-and-deferred.md)
- [Configuration](configuration.md)
- [Architecture: dependency resolution](../architecture.md#4-dependency-resolution)
