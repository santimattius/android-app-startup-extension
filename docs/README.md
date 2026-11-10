# Documentation

Documentation for `app-startup-extension` (Maven coordinates
`io.github.santimattius.android:app-startup-extension`), a Kotlin library that extends
[AndroidX App Startup](https://developer.android.com/topic/libraries/app-startup) with coroutine-based
asynchronous initializers.

> The 3.0 line is an ALPHA. See the [3.0 migration guide](migration-3.0.md) if you are upgrading from 2.x.

## Getting started

| Page | Description |
|---|---|
| [Getting started](getting-started.md) | Requirements, installation, first initializers, manifest registration, verification |

## Guides

| Page | Description |
|---|---|
| [Sync vs async initializers](guides/sync-vs-async-initializers.md) | What each type guarantees and how to choose |
| [Dependencies](guides/dependencies.md) | Mixed sync and async dependencies, ordering strategies, cycles |
| [Priorities and deferred startup](guides/priorities-and-deferred.md) | `StartupPriority`, the first-frame gate, priority inversion |
| [On-demand initialization](guides/on-demand-initialization.md) | `initializeComponent` and `doInitialize`, threading and locking |
| [Awaiting startup](guides/awaiting-startup.md) | `awaitAllStartJobs`, `onAppStartupLaunched`, `startupState` |
| [Configuration](guides/configuration.md) | Every `AppStartupConfig` option, defaults and timing of effect |
| [Testing](guides/testing.md) | Testing initializers with Robolectric and coroutines-test |
| [Troubleshooting](guides/troubleshooting.md) | ANRs, warnings, state semantics, cycle errors |
| [Migrating from AndroidX App Startup](guides/migrating-from-androidx.md) | Step-by-step manifest and code migration |

## Reference

| Page | Description |
|---|---|
| [API reference](api-reference.md) | Public API: types, members, options and defaults |
| [Observability](observability.md) | `metricsFlow`, trace sections, Macrobenchmark, dispatcher snapshots |

## Internals

| Page | Description |
|---|---|
| [Architecture](architecture.md) | Components, startup sequence, threading model, error handling |

## Migration

| Page | Description |
|---|---|
| [Migrating to 3.0](migration-3.0.md) | Breaking changes, before and after, checklist |
| [Migrating from AndroidX App Startup](guides/migrating-from-androidx.md) | Moving existing initializers to this library |
| [CHANGELOG](../CHANGELOG.md) | Release notes |

## Performance

These articles are written in Spanish.

| Page | Description |
|---|---|
| [App Startup Extension vs AndroidX App Startup](app-startup-extension-vs-androidx.md) | Comparison, configuration strategies and trade-offs |
| [Coroutine storm](coroutine-storm.md) | Why unbounded fan-out hurts, how to detect and mitigate it |
| [Performance analysis](performance-analysis.md) | Review of risks, memory and correctness findings in `core/` |
