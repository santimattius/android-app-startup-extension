# App Startup Extension

App Startup Extension is a library based on AndroidX App Startup that optimizes component
initialization in Android applications using Kotlin Coroutines. Its primary goal is to improve
startup time without blocking the main thread.

## Features

- **Synchronous Initialization:** Allows components to be initialized synchronously.
- **Asynchronous Initialization:** Supports asynchronous component initialization to avoid blocking
  the main thread.

## Installation

To add this library to your Android project, include the repository in your project-level
`build.gradle` file:

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

Then, add the dependency in your module-level `build.gradle` file:

```groovy
dependencies {
    implementation "module:${version}"
}
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

This function suspends execution until all asynchronous initialization tasks started by
AppStartupInitializer are completed. It ensures that any background
startup operations, such as data preloading or library initialization, finish before proceeding.

```kotlin
val appStartupInitializer = AppStartupInitializer.getInstance(appContext)

coroutineScope.launch {
    appStartupInitializer.awaitAllStartJobs()
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
Checks if all initialization jobs managed by AppStartupInitializer are completed. It returns true if
no jobs are currently active, otherwise false.

```kotlin

if (appStartupInitializer.isAllStartedJobsDone()) {
    Log.d("AppStartup", "All startup jobs have finished")
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
to [open an issue](https://github.com/santimattius/{{repository}}/issues) on GitHub. We are here to
help!

