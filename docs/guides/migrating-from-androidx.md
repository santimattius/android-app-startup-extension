# Migrating from AndroidX App Startup

This page shows how to move an existing initializer from `androidx.startup` to this library. For the
rationale and a comparison, see [App Startup Extension vs AndroidX App Startup](../app-startup-extension-vs-androidx.md)
(written in Spanish).

The two providers can coexist in one app. The sample app declares both, with the AndroidX entry under
`androidx.startup.InitializationProvider` and its own entries under this library's provider.

## Steps

### 1. Switch the provider in the manifest

**Before**

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <!-- This entry makes ExampleLoggerInitializer discoverable. -->
</provider>
```

**After**

```xml
<provider
    android:name="io.github.santimattius.android.startup.InitializationProvider"
    android:authorities="${applicationId}.app-startup-extension"
    android:exported="false"
    tools:node="merge">
    <!-- This entry makes ExampleLoggerInitializer discoverable. -->
</provider>
```

### 2. Change the `meta-data` value

Replace `androidx.startup` with `sync-initializer` (or `async-initializer` for a coroutine-based one):

```xml
<provider
    android:name="io.github.santimattius.android.startup.InitializationProvider"
    android:authorities="${applicationId}.app-startup-extension"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="com.santimattius.android.sample.migration.ExampleLoggerInitializer"
        android:value="sync-initializer" />
</provider>
```

### 3. Change the initializer implementation

**Before**

```kotlin
import androidx.startup.Initializer

class ExampleLoggerInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Initialization code
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
```

**After**

```kotlin
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer

class ExampleLoggerInitializer : StartupSyncInitializer<Unit> {
    override fun create(context: Context) {
        // Initialization code
    }

    override fun dependencies(): List<Class<out StartupSyncInitializer<*>>> = emptyList()
}
```

### 4. Move slow work to async initializers

Once an initializer is a `StartupSyncInitializer`, it still blocks the thread that initializes it. For work
that does not need to finish before the app is usable, implement `StartupAsyncInitializer` instead and
choose a dispatcher. See [Sync vs async initializers](sync-vs-async-initializers.md).

### 5. Await the async work

Async initializers are not awaited automatically. See [Awaiting startup](awaiting-startup.md).

## Checklist

- [ ] The manifest uses `io.github.santimattius.android.startup.InitializationProvider`.
- [ ] Each `<meta-data>` value is `sync-initializer` or `async-initializer`.
- [ ] Initializers implement the library's interfaces and have a public no-argument constructor.
- [ ] Dependencies are expressed with the matching types (`dependencies()` and, for async,
  `syncDependencies()`).
- [ ] Heavy work was moved off the provider path.
- [ ] Something awaits async startup.

## Next steps

- [Getting started](../getting-started.md)
- [Dependencies](dependencies.md)
- [Documentation index](../README.md)
