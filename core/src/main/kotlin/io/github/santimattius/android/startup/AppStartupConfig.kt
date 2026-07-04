package io.github.santimattius.android.startup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Controls how sync initializers are ordered before execution begins.
 *
 * - [Lazy] (default): DFS-based recursive resolution. Cycle detection happens mid-initialization,
 *   after some [StartupSyncInitializer.create] calls may have already run.
 *
 * - [Topological]: Kahn's algorithm runs before any [StartupSyncInitializer.create] is called.
 *   Cycles are detected and reported upfront with the names of all participating nodes.
 *   Redundant calls to already-initialized components are eliminated.
 */
sealed class SyncOrderingStrategy {
    object Lazy : SyncOrderingStrategy()
    object Topological : SyncOrderingStrategy()
}

/**
 * Controls how async initializers are validated and launched.
 *
 * - [Concurrent] (default): every discovered async initializer gets its own coroutine.
 *   Cycle detection happens lazily inside each coroutine's DFS chain.
 *
 * - [Validated]: builds the full async-to-async dependency graph (via [StartupAsyncInitializer.dependencies])
 *   before launching any coroutine. Cycles are detected and reported upfront with all participating
 *   node names. Only **root** initializers — those that no other async initializer depends on — get
 *   an explicit coroutine; their transitive dependencies are resolved recursively inside that coroutine,
 *   eliminating redundant coroutine launches.
 *
 * > [StartupAsyncInitializer.syncDependencies] are intentionally excluded from this graph: by the
 *   time async initializers start, all sync initializers discovered in the manifest have already
 *   completed on the main thread.
 */
sealed class AsyncInitializerStrategy {
    object Concurrent : AsyncInitializerStrategy()
    object Validated : AsyncInitializerStrategy()
}

/**
 * Immutable snapshot of the library's runtime configuration.
 *
 * Construct via [AppStartupInitializer.configure]:
 * ```kotlin
 * AppStartupInitializer.configure {
 *     debugLoggingEnabled            = BuildConfig.DEBUG
 *     strictModeCheckEnabled         = BuildConfig.DEBUG
 *     syncOrderingStrategy           = SyncOrderingStrategy.Topological
 *     asyncInitializerStrategy       = AsyncInitializerStrategy.Validated
 *     // Coroutine storm mitigation (all optional, default-off):
 *     maxConcurrentAsyncInitializers = 4                  // cap concurrent create() bodies
 *     defaultAsyncDispatcher         = Dispatchers.Default // library-wide default dispatcher
 *     strictModeConcurrencyThreshold = Runtime.getRuntime().availableProcessors()
 * }
 * ```
 */
class AppStartupConfig private constructor(
    val strictModeCheckEnabled: Boolean,
    val debugLoggingEnabled: Boolean,
    val syncOrderingStrategy: SyncOrderingStrategy,
    val asyncInitializerStrategy: AsyncInitializerStrategy,
    val maxConcurrentAsyncInitializers: Int?,
    val defaultAsyncDispatcher: CoroutineDispatcher,
    val strictModeConcurrencyThreshold: Int,
    val firstFrameSignal: FirstFrameSignal?,
    val deferredStartupTimeoutMs: Long,
) {
    class Builder {
        var strictModeCheckEnabled: Boolean = false
        var debugLoggingEnabled: Boolean = false
        var syncOrderingStrategy: SyncOrderingStrategy = SyncOrderingStrategy.Lazy
        var asyncInitializerStrategy: AsyncInitializerStrategy = AsyncInitializerStrategy.Concurrent

        /**
         * Maximum number of async initializers allowed to execute `create()` concurrently.
         * `null` (default) means unbounded — current, unchanged behavior. Values `<= 0` are
         * normalized to `null` (unbounded) at [build] time rather than deadlocking or throwing.
         */
        var maxConcurrentAsyncInitializers: Int? = null

        /**
         * Library-wide default dispatcher used to run async initializers that do not override
         * [io.github.santimattius.android.startup.initializer.StartupAsyncInitializer.dispatcher].
         * Defaults to [Dispatchers.Default], preserving current behavior.
         */
        var defaultAsyncDispatcher: CoroutineDispatcher = Dispatchers.Default

        /**
         * Concurrency level above which strict mode logs a warning. Defaults to the number of
         * available processor cores, since [Dispatchers.Default]'s parallelism is core-bound.
         */
        var strictModeConcurrencyThreshold: Int = Runtime.getRuntime().availableProcessors()

        /**
         * Optional [FirstFrameSignal] that gates [StartupPriority.DEFERRED] async initializers.
         * `null` (default) means the scheduler resolves the internal Android first-draw default
         * lazily — keeping this static config free of any `Context`/`Activity` reference. Inject a
         * fake here to drive deferred scheduling deterministically under test.
         */
        var firstFrameSignal: FirstFrameSignal? = null

        /**
         * Upper bound (ms) the deferred-startup gate waits for the first frame before flushing
         * [StartupPriority.DEFERRED] work anyway. Guarantees headless/no-UI processes (which never
         * draw a frame) still run their deferred initializers. Defaults to `5_000L`.
         *
         * A value `<= 0` makes the gate return immediately (`withTimeoutOrNull(0)`), so deferred
         * work flushes without waiting for a frame — a documented escape hatch, not a bug.
         */
        var deferredStartupTimeoutMs: Long = 5_000L

        internal fun build() = AppStartupConfig(
            strictModeCheckEnabled = strictModeCheckEnabled,
            debugLoggingEnabled = debugLoggingEnabled,
            syncOrderingStrategy = syncOrderingStrategy,
            asyncInitializerStrategy = asyncInitializerStrategy,
            maxConcurrentAsyncInitializers = maxConcurrentAsyncInitializers?.takeIf { it > 0 },
            defaultAsyncDispatcher = defaultAsyncDispatcher,
            strictModeConcurrencyThreshold = strictModeConcurrencyThreshold,
            firstFrameSignal = firstFrameSignal,
            deferredStartupTimeoutMs = deferredStartupTimeoutMs,
        )
    }

    internal companion object {
        fun default() = Builder().build()
    }
}
