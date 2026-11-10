# Migrating to 3.0

Version 3.0 changes the execution contract of `StartupSyncInitializer`. This guide summarizes the change,
shows what to audit, and gives a checklist.

> **Pre-release.** 3.0 is currently published as an ALPHA (`3.0.0-ALPHA01`). The latest stable release is
> `2.2.0`.

## Summary of breaking changes

| # | Change | Who is affected |
|---|---|---|
| 1 | `StartupSyncInitializer.create()` runs on the **requesting thread** instead of always being dispatched to the main thread. The caller stays blocked until `create()` returns. | Code that calls `initializeComponent()` or `doInitialize()` from a worker thread; sync initializers that use main-thread-only APIs |
| 2 | Synchronous initializers declared through `StartupAsyncInitializer.syncDependencies()` run in the **current coroutine context**, before the async initializer switches to its configured dispatcher. | Async initializers whose sync dependencies assumed the main thread |

Not breaking, but new in behavior:

- Sync initializers are coordinated **per component** rather than through one global execution lock.
  Unrelated components can initialize concurrently. Concurrent requests for the same component still wait
  for, and share, one retained result.
- Cross-thread dependency cycles between sync initializers fail with `StartupExtensionException` instead of
  hanging.

The CHANGELOG lists no new or removed public declarations for 3.0.

## What stays the same

Provider-driven startup is unchanged in practice. Android invokes `InitializationProvider.onCreate()` on the
main thread, so manifest-declared sync initializers still run on main.

## Why it changed

Synchronous describes completion semantics, not thread affinity. Running on the caller avoids an unnecessary
main-thread handoff and prevents unrelated synchronous initializers from competing for one global execution
lock.

## Before and after

| Entry point | 2.x | 3.0 |
|---|---|---|
| Manifest-declared sync initializer | Main thread | Main thread (unchanged) |
| `initializeComponent()` / `doInitialize()` from a worker thread | `create()` posted to main | `create()` runs on the calling thread |
| `StartupAsyncInitializer.syncDependencies()` | Sync initializers were dispatched to main | Run in the current coroutine context |

Example. Before 3.0, this background call caused `create()` to be posted to main:

```kotlin
withContext(Dispatchers.Default) {
    startup.initializeComponent(MainOnlyInitializer::class.java)
}
```

In 3.0, `create()` runs on `Dispatchers.Default`. If main affinity is required, make the call site explicit:

```kotlin
withContext(Dispatchers.Main.immediate) {
    startup.initializeComponent(MainOnlyInitializer::class.java)
}
```

If the work is naturally asynchronous, prefer `StartupAsyncInitializer` and select its dispatcher through
`dispatcher()`:

```kotlin
class MainOnlyInitializer : StartupAsyncInitializer<Component> {
    override fun dispatcher() = Dispatchers.Main.immediate

    override suspend fun create(context: Context): Component = Component(context)
}
```

## Migration checklist

1. Audit every `StartupSyncInitializer.create()` for main-thread-only APIs.
2. Audit every background call to `initializeComponent()` and `doInitialize()`.
3. Audit `StartupAsyncInitializer.syncDependencies()` declarations. Do not assume those synchronous
   dependencies run on main.
4. Keep provider-driven synchronous work short; it still runs during `ContentProvider.onCreate()` on main.
5. Move blocking I/O or expensive computation to `StartupAsyncInitializer` with an appropriate dispatcher.
6. Run startup and on-demand initialization tests with the same dispatchers used by production callers.

## Main-thread-only initialization

If a component truly requires Android's main thread, make that affinity explicit instead of relying on the
synchronous marker:

- invoke on-demand synchronous initialization from main; or
- model the component as a `StartupAsyncInitializer` whose `dispatcher()` returns `Dispatchers.Main.immediate`.

Do not block a worker-owned synchronous initializer waiting for main while main is synchronously requesting
that same initializer. Synchronous exactly-once semantics require the second caller to wait for the in-flight
result, so that external wait cycle cannot be resolved by the library.

## Documentation changes in 3.0

Source-level notes in the 2.x KDoc that described sync initializers as "main-thread only" are superseded by
this guide and the [architecture document](architecture.md#5-threading-model-30).

## Next steps

- [Sync vs async initializers](guides/sync-vs-async-initializers.md)
- [On-demand initialization](guides/on-demand-initialization.md)
- [Testing](guides/testing.md)
- [Documentation index](README.md)
