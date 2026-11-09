# Migrating to 3.0

Version 3.0 changes the execution contract of `StartupSyncInitializer`.

## What changed

In 2.x, synchronous initializers were dispatched to Android's main thread even when initialization was requested from a worker thread. In 3.0, a synchronous initializer runs on the requesting thread and returns only after `create()` completes.

Provider-driven startup remains unchanged in practice: Android invokes `InitializationProvider.onCreate()` on the main thread, so manifest-declared synchronous initializers still run there.

The behavior changes for:

- `initializeComponent()` or `doInitialize()` called from a worker thread;
- synchronous dependencies declared by `StartupAsyncInitializer.syncDependencies()`, which run in the current coroutine context before the async initializer switches to its configured dispatcher.

## Why it changed

Synchronous describes completion semantics, not thread affinity. Running on the caller avoids an unnecessary main-thread handoff and prevents unrelated synchronous initializers from competing for one global execution lock.

Different initializer classes are coordinated independently. Concurrent requests for the same class still wait for one retained result, and cross-thread dependency cycles fail instead of hanging.

## Migration checklist

1. Audit every `StartupSyncInitializer.create()` for main-thread-only APIs.
2. Audit every background call to `initializeComponent()` and `doInitialize()`.
3. Audit `StartupAsyncInitializer.syncDependencies()` declarations. Do not assume those synchronous dependencies run on main.
4. Keep provider-driven synchronous work short; it still runs during `ContentProvider.onCreate()` on main.
5. Move blocking I/O or expensive computation to `StartupAsyncInitializer` with an appropriate dispatcher.
6. Run startup and on-demand initialization tests with the same dispatchers used by production callers.

## Main-thread-only initialization

If a component truly requires Android's main thread, make that affinity explicit instead of relying on the synchronous marker:

- invoke on-demand synchronous initialization from main; or
- model the component as a `StartupAsyncInitializer` whose `dispatcher()` returns `Dispatchers.Main.immediate`.

Do not block a worker-owned synchronous initializer waiting for main while main is synchronously requesting that same initializer. Synchronous exactly-once semantics require the second caller to wait for the in-flight result, so that external wait cycle cannot be resolved by the library.

## Example

Before 3.0, this background call caused `create()` to be posted to main:

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

If the work is naturally asynchronous, prefer `StartupAsyncInitializer` and select its dispatcher through `dispatcher()`.
