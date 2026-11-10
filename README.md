![architecture - Startup Extension Initializer(1)](https://github.com/user-attachments/assets/9d44a786-7638-44de-952a-60059984abda)

[![Latest Release](https://maven-badges.sml.io/sonatype-central/io.github.santimattius.android/app-startup-extension/badge.svg?subject=Latest%20Release&color=blue)](https://maven-badges.sml.io/sonatype-central/io.github.santimattius.android/app-startup-extension/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)

# App Startup Extension

App Startup Extension is a Kotlin library that extends
[AndroidX App Startup](https://developer.android.com/topic/libraries/app-startup) with asynchronous,
coroutine-based initializers. It keeps the single-provider, manifest-driven model and adds a way to move
slow initialization off the startup critical path.

> **3.0 ALPHA.** The 3.0 line is a pre-release (`3.0.0-ALPHA01`). In 3.0, `StartupSyncInitializer.create()`
> runs on the thread that requests initialization instead of always on the main thread. If you are upgrading
> from 2.x, read the [3.0 migration guide](docs/migration-3.0.md).

## Why

AndroidX App Startup runs every `Initializer.create()` as a blocking call. Heavy work there competes with
the thread that runs the chain, and teams end up hand-rolling threading and synchronization.

This library separates the two kinds of work:

- **Sync initializers** for what must be ready right away. The caller blocks until `create()` returns.
- **Async initializers** with a `suspend` `create()`, an optional per-initializer dispatcher, and no blocking
  of startup. Await them where you need them.

See [App Startup Extension vs AndroidX App Startup](docs/app-startup-extension-vs-androidx.md) for a
detailed comparison (in Spanish).

## Features

- **Sync and async initializers** with mixed dependencies (`dependencies()` and `syncDependencies()`).
- **Per-initializer dispatcher** with a library-wide default (`defaultAsyncDispatcher`).
- **Priorities:** `StartupPriority.DEFERRED` launches work after the first frame (or a timeout).
- **Coroutine storm mitigation:** optional cap on concurrent async `create()` bodies
  (`maxConcurrentAsyncInitializers`).
- **Failure isolation:** one failing async initializer does not cancel its siblings.
- **Startup lifecycle:** `startupState` (`IDLE`, `IN_PROGRESS`, `COMPLETED`, `ERROR`), `awaitAllStartJobs()`
  with an optional timeout, and `onAppStartupLaunched { }`.
- **On-demand initialization:** `initializeComponent` and `doInitialize` for sync and async components.
- **Cycle detection:** up-front for async graphs and for sync graphs with `SyncOrderingStrategy.Topological`.
- **Metrics:** per-initializer timing, outcome, dispatcher and concurrency data as a bounded `SharedFlow`.
- **System tracing:** `Startup::<phase>::<FQN>` sections for Perfetto and Macrobenchmark.
- **StrictMode and debug logging** for development builds.
- **Transient initializers:** `retainAfterStartup() = false` releases the result after initialization.
- **R8 and ProGuard:** consumer keep rules are bundled.

## Installation

Requires min SDK 24 and JVM target 17. Add Maven Central if it is not already a repository:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Latest stable release:

```kotlin
dependencies {
    implementation("io.github.santimattius.android:app-startup-extension:2.3.0")
}
```

3.0 pre-release (ALPHA):

```kotlin
dependencies {
    implementation("io.github.santimattius.android:app-startup-extension:3.0.0-ALPHA01")
}
```

Groovy DSL and version catalog snippets are in [Getting started](docs/getting-started.md#installation).

## Quick start

Define a sync and an async initializer:

```kotlin
class LoggerInitializer : StartupSyncInitializer<Unit> {
    override fun create(context: Context) {
        Log.d("Startup", "logger ready")
    }
}

class CacheWarmupInitializer : StartupAsyncInitializer<Unit> {
    override fun dispatcher() = Dispatchers.IO

    override suspend fun create(context: Context) {
        // Disk or network work, off the main thread
    }
}
```

Register them in `AndroidManifest.xml`:

```xml
<provider
    android:name="io.github.santimattius.android.startup.InitializationProvider"
    android:authorities="${applicationId}.app-startup-extension"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="com.example.app.LoggerInitializer"
        android:value="sync-initializer" />
    <meta-data
        android:name="com.example.app.CacheWarmupInitializer"
        android:value="async-initializer" />
</provider>
```

Wait for async startup where your app needs it:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val startup = AppStartupInitializer.getInstance(this)
        CoroutineScope(Dispatchers.Main).launch {
            startup.onAppStartupLaunched { Log.d("App", "startup finished") }
        }
    }
}
```

Optional configuration goes in `Application.attachBaseContext`:

```kotlin
AppStartupInitializer.configure {
    debugLoggingEnabled = BuildConfig.DEBUG
    strictModeCheckEnabled = BuildConfig.DEBUG
}
```

> **Threading.** Provider-driven sync initializers run on the main thread with no timeout, so keep them
> short. Put heavy work in async initializers.

## Documentation

The full documentation index is in [docs/README.md](docs/README.md).

| Topic | Page |
|---|---|
| Setup and first initializers | [Getting started](docs/getting-started.md) |
| Choosing an initializer type | [Sync vs async initializers](docs/guides/sync-vs-async-initializers.md) |
| Ordering and cycles | [Dependencies](docs/guides/dependencies.md) |
| Deferred work | [Priorities and deferred startup](docs/guides/priorities-and-deferred.md) |
| Requesting components yourself | [On-demand initialization](docs/guides/on-demand-initialization.md) |
| Waiting and state | [Awaiting startup](docs/guides/awaiting-startup.md) |
| All options | [Configuration](docs/guides/configuration.md) |
| Tests | [Testing](docs/guides/testing.md) |
| Problems | [Troubleshooting](docs/guides/troubleshooting.md) |
| Metrics, traces, benchmarks | [Observability](docs/observability.md) |
| Public API | [API reference](docs/api-reference.md) |
| Internals | [Architecture](docs/architecture.md) |
| From AndroidX App Startup | [Migrating from AndroidX](docs/guides/migrating-from-androidx.md) |
| From 2.x | [Migrating to 3.0](docs/migration-3.0.md) |
| Release notes | [CHANGELOG](CHANGELOG.md) |

The `app/` module is a sample application with a Compose UI that exercises the features above.

## Contributing

Contributions are welcome.

1. Fork the repository.
2. Create a branch (`git checkout -b feature/new-feature`).
3. Implement your changes following the existing style and conventions, with tests.
4. Commit with a clear message (`git commit -am 'Add new feature'`).
5. Push the branch (`git push origin feature/new-feature`).
6. Open a pull request describing your changes.

Run `./gradlew build` and `./gradlew :core:apiCheck` before opening a pull request.

## License and contact

Released under the [Apache License 2.0](LICENSE).

Questions, issues or suggestions: [open an issue](https://github.com/santimattius/android-app-startup-extension/issues).
