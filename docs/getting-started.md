# Getting started

This page takes you from an empty dependency list to a working sync and async initializer, and shows how
to confirm that startup behaves as expected.

> **Pre-release notice.** The 3.0 line is in ALPHA. The latest stable release is `2.2.0`; the pre-release
> coordinate is `3.0.0-ALPHA01`. If you are upgrading from 2.x, read the [3.0 migration guide](migration-3.0.md)
> first.

## Requirements

| Requirement | Value | Source |
|---|---|---|
| Min SDK | 24 | `androidMinSdkVersion` in `gradle.properties` |
| JVM target | 17 | `jvmToolchain(17)` and `JavaVersion.VERSION_17` in `core/build.gradle.kts` |
| Language | Kotlin (initializers use `suspend` functions and coroutine dispatchers) | |

The library is a regular AAR. If your own code uses coroutine APIs (`Dispatchers`, `delay`, `Flow`), declare
`kotlinx-coroutines` in your module explicitly instead of relying on a transitive dependency.

## Installation

Make sure Maven Central is a repository (it is by default in new projects):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Add the dependency using one of the following styles.

**Gradle Kotlin DSL**

```kotlin
dependencies {
    implementation("io.github.santimattius.android:app-startup-extension:2.2.0")
}
```

**Gradle Groovy DSL**

```groovy
dependencies {
    implementation "io.github.santimattius.android:app-startup-extension:2.2.0"
}
```

**Version catalog** (`gradle/libs.versions.toml`)

```toml
[versions]
appStartupExtension = "2.2.0"

[libraries]
app-startup-extension = { group = "io.github.santimattius.android", name = "app-startup-extension", version.ref = "appStartupExtension" }
```

```kotlin
dependencies {
    implementation(libs.app.startup.extension)
}
```

To try the 3.0 pre-release, replace the version with `3.0.0-ALPHA01`.

The library does not declare `androidx.appcompat:appcompat` or `com.google.android.material:material`, so
they are not transitive dependencies of your app. If your app relied on this library pulling them in,
declare them yourself.

## Your first sync initializer

A sync initializer runs `create()` and blocks the requesting thread until it returns. During
provider-driven startup that thread is the main thread, so keep it short.

```kotlin
import android.content.Context
import android.util.Log
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer

class LoggerInitializer : StartupSyncInitializer<Unit> {
    override fun create(context: Context) {
        Log.d("LoggerInitializer", "logger ready")
    }
}
```

The class must be concrete and have a public no-argument constructor: the library instantiates it by name
through reflection. See [Sync vs async initializers](guides/sync-vs-async-initializers.md) for when to use
this type.

## Your first async initializer

An async initializer has a `suspend` `create()` and runs on a coroutine scope owned by the library. Startup
does not wait for it.

```kotlin
import android.content.Context
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import kotlinx.coroutines.Dispatchers

class CacheWarmupInitializer : StartupAsyncInitializer<Unit> {
    override fun dispatcher() = Dispatchers.IO

    override suspend fun create(context: Context) {
        // Disk or network work goes here, off the main thread.
    }
}
```

`dispatcher()` is optional. When it returns `null` (the default), the library uses
`AppStartupConfig.defaultAsyncDispatcher`, which defaults to `Dispatchers.Default`.

## Register the initializers in the manifest

Declare each initializer as `<meta-data>` under `InitializationProvider`. The value is `sync-initializer` or
`async-initializer`. Add `xmlns:tools` to the manifest root and keep `tools:node="merge"` so your entries are
merged with the provider declared by the library.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application>
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
    </application>
</manifest>
```

An initializer that is only reached through another initializer's `dependencies()` does not need a manifest
entry. See [Dependencies](guides/dependencies.md).

## Wait for async startup to finish

Async initializers are launched at process start and are not awaited automatically. Await them where your
app needs them, for example in `Application.onCreate`:

```kotlin
import android.app.Application
import android.util.Log
import io.github.santimattius.android.startup.AppStartupInitializer
import io.github.santimattius.android.startup.onAppStartupLaunched
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val startup = AppStartupInitializer.getInstance(this)
        CoroutineScope(Dispatchers.Main).launch {
            startup.onAppStartupLaunched {
                Log.d("App", "all startup jobs finished")
            }
        }
    }
}
```

`onAppStartupLaunched` suspends until all non-deferred start jobs finish and rethrows the first failure.
`DEFERRED` initializers are not awaited. See [Awaiting startup](guides/awaiting-startup.md).

## Configure the library (optional)

Call `configure` before the provider runs, which means in `Application.attachBaseContext`:

```kotlin
override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)
    AppStartupInitializer.configure {
        debugLoggingEnabled = BuildConfig.DEBUG
        strictModeCheckEnabled = BuildConfig.DEBUG
    }
}
```

All options are listed in [Configuration](guides/configuration.md).

## Verify that it works

1. **Logs.** With `debugLoggingEnabled = true`, the library writes discovery and initialization messages
   with the tag `StartupLogger` (`Discovered <class>`, `Initializing <class>`, `Initialized <class>`):

   ```bash
   adb logcat -s StartupLogger
   ```

2. **Startup state.** `AppStartupInitializer.getInstance(context).startupState` is a `StateFlow<StartupState>`.
   It moves to `COMPLETED` or `ERROR` only after something awaits startup
   (`awaitAllStartJobs()` or `onAppStartupLaunched { }`). See [Troubleshooting](guides/troubleshooting.md).

3. **Metrics.** Collect `metricsFlow` to see the duration, thread and outcome of every initializer:

   ```kotlin
   lifecycleScope.launch {
       AppStartupInitializer.getInstance(applicationContext).metricsFlow.collect { m ->
           Log.d("Startup", "${m.initializerFqn} ${m.durationMs}ms async=${m.isAsync} ok=${m.success}")
       }
   }
   ```

4. **System trace.** The library writes `Startup::...` sections that show up in Perfetto. See
   [Observability](observability.md).

## R8 and ProGuard

No manual rules are required. The library ships `core/consumer-rules.pro`, which keeps every class that
implements `StartupSyncInitializer` or `StartupAsyncInitializer` (with its no-argument constructor) and
`InitializationProvider`. This matters because initializers are located by class name from manifest
metadata and created by reflection. Details are in the [architecture document](architecture.md#11-r8--consumer-rules).

The consumer rules matter most for initializers that are reached only through another initializer's
`dependencies()` and are not declared in the manifest, because they are instantiated by name.
Manifest-declared initializers are also kept by AGP's aapt2 rules, so they do not prove that the consumer
rules work. This repository's CI builds the sample with R8 and runs `.github/scripts/check-r8-initializers.sh`,
which asserts that `InitializationProvider`, the two manifest-declared sample initializers and a
dependency-only initializer survive with their no-argument constructors and are not renamed.

## Common pitfalls

- **Heavy work in a sync initializer.** Provider-driven sync initializers run on the main thread with no
  timeout, which can cause an ANR. Move I/O and CPU-heavy work to an async initializer.
- **`configure` called too late.** Options read when the singleton is created
  (`maxConcurrentAsyncInitializers`, `metricsReplayCapacity`) are not affected by later `configure` calls.
  Call it in `attachBaseContext`.
- **Forgetting to await.** Nothing awaits async startup for you. Without an await call, `startupState`
  stays `IN_PROGRESS` and failures of async initializers are never surfaced.
- **Abstract classes or interfaces in the manifest.** They fail with `StartupExtensionException`
  ("expected a concrete class").
- **Mixed-up manifest values.** The value must be exactly `sync-initializer` or `async-initializer`. A class
  listed with the wrong kind is silently ignored.
- **Leaking UI references.** Collect `metricsFlow` from a lifecycle-aware scope and never keep an
  `Activity` or `Fragment` in a `FirstFrameSignal` or collector. See [Observability](observability.md).

## Next steps

- [Sync vs async initializers](guides/sync-vs-async-initializers.md): choose the right type.
- [Dependencies](guides/dependencies.md): order your initializers.
- [Configuration](guides/configuration.md): tune the library.
- [Migrating from AndroidX App Startup](guides/migrating-from-androidx.md)
- [Documentation index](README.md)
