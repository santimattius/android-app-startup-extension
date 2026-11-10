# Testing

This page shows how to test initializers and code that depends on them. The library's own tests use
JUnit 4, Robolectric (no emulator), `kotlinx-coroutines-test` and MockK, and run on the JVM.

## What you can and cannot construct

`AppStartupInitializer` has an `internal` constructor. The library's own tests live in the same module and
build instances directly, with a test dispatcher injected into the engine. Consumers cannot do that: from
your code you obtain the singleton with `AppStartupInitializer.getInstance(context)`.

That leads to two practical strategies:

1. **Test each initializer in isolation.** `create(context)` is a plain function, so call it directly. This is
   the recommended default.
2. **Test the wiring on a device or with Robolectric**, where the real provider runs from your manifest.

## Test dependencies

```kotlin
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:<version>")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:<version>")
    testImplementation("io.mockk:mockk:<version>")
}
```

Use the versions your project already resolves; see `gradle/libs.versions.toml` in this repository for the
versions its tests use.

## Testing a sync initializer

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class ConfigInitializerTest {

    @Test
    fun `create loads the configuration`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val config = ConfigInitializer().create(context)

        assertEquals("expected", config.value)
    }

    @Test
    fun `declares its dependencies`() {
        assertEquals(
            listOf(LoggerInitializer::class.java),
            ConfigInitializer().dependencies(),
        )
    }
}
```

## Testing an async initializer

Use `runTest` and inject whatever the initializer needs. Because `create()` is `suspend`, virtual time works
as usual.

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class CacheWarmupInitializerTest {

    @Test
    fun `create warms the cache`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()

        CacheWarmupInitializer().create(context)

        assertTrue(ImageCache.isWarm)
    }

    @Test
    fun `runs on IO and is not deferred`() {
        val initializer = CacheWarmupInitializer()

        assertEquals(Dispatchers.IO, initializer.dispatcher())
        assertEquals(StartupPriority.NORMAL, initializer.priority())
    }
}
```

Keep a dispatcher injectable in the code that `create()` calls, so tests can replace `Dispatchers.IO` with a
`StandardTestDispatcher`.

## Driving deferred initializers

`FirstFrameSignal` is a public seam. A `CompletableDeferred`-backed fake lets a test decide when "the first
frame" happens:

```kotlin
class FakeFirstFrameSignal : FirstFrameSignal {
    val frame = CompletableDeferred<Unit>()
    override suspend fun await() = frame.await()
}

val signal = FakeFirstFrameSignal()
AppStartupInitializer.configure {
    firstFrameSignal = signal
    deferredStartupTimeoutMs = 60_000L
}
// ... start the app under test, then:
signal.frame.complete(Unit)
```

## Resetting configuration between tests

`configure` replaces a process-wide static. Reset it in `@After` so one test's settings do not leak into the
next; the library's own tests do exactly this:

```kotlin
@After
fun tearDown() {
    AppStartupInitializer.configure { }
}
```

Remember that `maxConcurrentAsyncInitializers` and `metricsReplayCapacity` are read when the singleton is
constructed, so changing them between tests in the same process has no effect on an existing singleton.

## Observing results in tests

If the singleton has run, you can assert on startup outcomes through the public API:

```kotlin
val startup = AppStartupInitializer.getInstance(context)
runTest {
    startup.awaitAllStartJobs()
    assertEquals(StartupState.COMPLETED, startup.startupState.value)
    val metrics = startup.metricsFlow.replayCache
    assertTrue(metrics.none { !it.success })
}
```

`metricsFlow` is a `SharedFlow`, so `replayCache` holds the most recent `metricsReplayCapacity` metrics.

## Tips

- Run startup and on-demand tests with the same dispatchers production callers use, as recommended in the
  [3.0 migration checklist](../migration-3.0.md#migration-checklist).
- For an initializer that touches Android APIs, Robolectric is usually enough; avoid hand-mocking `Context`
  unless the initializer needs very little from it.
- Fake initializers are defined inline in each test file in this repository; there is no shared fixtures
  module to depend on.

## Next steps

- [Troubleshooting](troubleshooting.md)
- [Observability](../observability.md)
- [Priorities and deferred startup](priorities-and-deferred.md)
