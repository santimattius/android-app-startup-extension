![architecture - Startup Extension Initializer(1)](https://github.com/user-attachments/assets/9d44a786-7638-44de-952a-60059984abda)

[![Latest Release](https://maven-badges.sml.io/sonatype-central/io.github.santimattius.android/app-startup-extension/badge.svg?subject=Latest%20Release&color=blue)](https://maven-badges.sml.io/sonatype-central/io.github.santimattius.android/app-startup-extension/)
# App Startup Extension

App Startup Extension is a library based on AndroidX App Startup that optimizes component
initialization in Android applications using Kotlin Coroutines. Its primary goal is to improve
startup time without blocking the main thread.

## Features

- **Synchronous Initialization:** Allows components to be initialized synchronously.
- **Asynchronous Initialization:** Supports asynchronous component initialization to avoid blocking
  the main thread.
- **Startup State Lifecycle:** Observe the startup lifecycle (`IDLE → IN_PROGRESS → COMPLETED / ERROR`) via a `StateFlow`.
- **Timeout Support:** Await all startup jobs with a configurable timeout.
- **Metrics:** Per-initializer timing and success/failure reporting via a listener.
- **Debug Logging:** Enable or disable library log output at runtime.
- **Cross-type Dependencies:** Async initializers can now declare sync initializer dependencies.
- **Per-initializer Dispatcher:** Each async initializer can specify its own `CoroutineDispatcher`.
- **R8/ProGuard Rules:** Consumer keep rules are bundled — no manual configuration required.

## Installation

To add this library to your Android project, include the repository in your project-level
`build.gradle` file:

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
```

Then, add the dependency in your module-level `build.gradle` file:

```groovy
dependencies {
    implementation("io.github.santimattius.android:app-startup-extension:${version}")

```

Replace `version` with the desired library version.

## Usage

To define an initializer, implement `StartupSyncInitializer` or `StartupAsyncInitializer`.

### Example: `StartupSyncInitializer`

```kotlin
class SyncTestInitializer : StartupSyncInitializer<Unit> {
    override fun create(context: Context) {
        Log.d("SyncTestInitializer", "TestInitializer created")
    }
}
```

> **Note:** `StartupSyncInitializer` runs on the main thread during app startup.

### Example: `StartupAsyncInitializer`

```kotlin
class AsyncTestInitializer : StartupAsyncInitializer<Unit> {
    override suspend fun create(context: Context) {
        Log.d("AsyncTestInitializer", "AsyncTestInitializer start")
        delay(5000)
        Log.d("AsyncTestInitializer", "AsyncTestInitializer end")
    }
}
```

#### Per-initializer Dispatcher

Override `dispatcher()` to pin the initializer's `create()` to a specific thread pool. Defaults to `Dispatchers.Default`.

```kotlin
class DatabaseInitializer : StartupAsyncInitializer<Unit> {
    override fun dispatcher() = Dispatchers.IO

    override suspend fun create(context: Context) {
        // runs on Dispatchers.IO
    }
}
```

#### Cross-type Dependencies

An async initializer can declare that certain sync initializers must complete before its `create()` is called by overriding `syncDependencies()`.

```kotlin
class AnalyticsInitializer : StartupAsyncInitializer<Unit> {
    override fun syncDependencies() = listOf(ConfigInitializer::class.java)

    override suspend fun create(context: Context) {
        // ConfigInitializer.create() is guaranteed to have finished
    }
}
```

### Registering in `AndroidManifest.xml`

```xml

<provider android:authorities="${applicationId}.startup-extension" android:exported="false"
    android:name="io.github.santimattius.android.startup.InitializationProvider" tools:node="merge">

    <meta-data android:name="com.santimattius.android.sample.SyncTestInitializer"
        android:value="sync-initializer" />

    <meta-data android:name="com.santimattius.android.sample.AsyncTestInitializer"
        android:value="async-initializer" />
</provider>
```

## Manual Initialization

You can also manually initialize components:

```kotlin
val initializer = AppStartupInitializer.getInstance(this@MainActivity)

// Synchronous initialization
initializer.doInitialize<Unit>(SyncTestInitializer::class.java)

// Asynchronous initialization
coroutineScope.launch {
    initializer.doInitialize<Unit>(AsyncTestInitializer::class.java)
}
```

## Utility Functions

### awaitAllStartJobs()

Suspends until all asynchronous initialization tasks started by `AppStartupInitializer` are completed.

```kotlin
val appStartupInitializer = AppStartupInitializer.getInstance(appContext)

coroutineScope.launch {
    appStartupInitializer.awaitAllStartJobs()
}
```

### awaitAllStartJobs(timeoutMs)

Same as above but throws `TimeoutCancellationException` if the jobs do not complete within the given
number of milliseconds. The underlying jobs continue running in the background — only the wait is cancelled.

```kotlin
coroutineScope.launch {
    appStartupInitializer.awaitAllStartJobs(timeoutMs = 5_000)
}
```

### onAppStartupLaunched

Executes a given block of code only after all startup jobs have finished. This is useful for running
code that depends on the completion of initialization processes.

```kotlin
coroutineScope.launch {
    appStartupInitializer.onAppStartupLaunched {
        Log.d("AppStartup", "All initialization tasks completed")
    }
}
```

### isAllStartedJobsDone

Checks if all initialization jobs managed by `AppStartupInitializer` are completed. Returns `true` if
no jobs are currently active, `false` otherwise.

```kotlin
if (appStartupInitializer.isAllStartedJobsDone()) {
    Log.d("AppStartup", "All startup jobs have finished")
}
```

### startupState

A hot `StateFlow<StartupState>` that reflects the current startup lifecycle. Collect it to react to
state changes without polling.

```
IDLE → IN_PROGRESS → COMPLETED
                   ↘ ERROR       (on first job failure; sibling jobs continue)
```

```kotlin
coroutineScope.launch {
    appStartupInitializer.startupState.collect { state ->
        when (state) {
            StartupState.COMPLETED -> Log.d("AppStartup", "Startup finished")
            StartupState.ERROR     -> Log.e("AppStartup", "Startup failed")
            else -> Unit
        }
    }
}
```

### Metrics

Register a `StartupMetricsListener` to receive timing and outcome data after each initializer runs.
Set `metricsListener` before startup begins (e.g. in `Application.attachBaseContext`).

```kotlin
AppStartupInitializer.getInstance(context).metricsListener =
    StartupMetricsListener { metric ->
        Log.d(
            "Metrics",
            "${metric.initializerName} took ${metric.durationMs}ms " +
            "(async=${metric.isAsync}, success=${metric.success})"
        )
    }
```

### Debug Logging

Enable library log output at runtime. Call this before the `InitializationProvider` runs to capture
discovery and initialization messages. Logging is **disabled by default**.

```kotlin
class MyApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AppStartupInitializer.enableDebugLogging(BuildConfig.DEBUG)
    }
}
```

## Migration from AndroidX App Startup to App Startup Extension

If you are already using AndroidX App Startup, follow these steps to migrate to App Startup
Extension:

1. Modify `AndroidManifest.xml`:

   **Before:**

   ```xml
   <provider android:name="androidx.startup.InitializationProvider"
       android:authorities="${applicationId}.androidx-startup" 
       android:exported="false" 
       tools:node="merge" >
      <!-- This entry makes ExampleLoggerInitializer discoverable. -->
   </provider>
   ```

   **After:**

   ```xml
   <provider android:name="io.github.santimattius.android.startup.InitializationProvider"
       android:authorities="${applicationId}.app-startup-extension" 
       android:exported="false" 
       tools:node="merge" >
      <!-- This entry makes ExampleLoggerInitializer discoverable. -->
   </provider>
   ```

2. Change the `meta-data` value from `androidx.startup` to `sync-initializer`.
    ```xml
           <provider
            android:name="io.github.santimattius.android.startup.InitializationProvider"
            android:authorities="${applicationId}.app-startup-extension"
            android:exported="false"
            tools:node="merge">
            <!-- This entry makes ExampleLoggerInitializer discoverable. -->
            <meta-data  android:name="com.santimattius.android.sample.migration.ExampleLoggerInitializer"
                android:value="sync-initializer" />
        </provider>
    ```
3. Modify the `Initializer` implementation:

   **Before:**

   ```kotlin
   import androidx.startup.Initializer

   class ExampleLoggerInitializer : Initializer<Unit> {
       override fun create(context: Context) {
           // Initialization code
       }

       override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
   }
   ```

   **After:**

   ```kotlin
   import io.github.santimattius.android.startup.initializer.StartupSyncInitializer

   class ExampleLoggerInitializer : StartupSyncInitializer<Unit> {
       override fun create(context: Context) {
           // Initialization code
       }

       override fun dependencies(): List<Class<out StartupSyncInitializer<*>>> = emptyList()
   }
   ```

## Contributions

Contributions are welcome! To contribute to this library, follow these steps:

1. Fork the repository.
2. Create a new branch for your contribution (`git checkout -b feature/new-feature`).
3. Implement your changes following the style guides and coding conventions.
4. Commit your changes with a clear description (`git commit -am 'Add new feature'`).
5. Push your changes to your repository (`git push origin feature/new-feature`).
6. Create a pull request on GitHub, describing your changes.

## Contact

If you have questions, issues, or suggestions about this library, feel free
to [open an issue](https://github.com/santimattius/android-app-startup-extension/issues) on GitHub. We are here to
help!

