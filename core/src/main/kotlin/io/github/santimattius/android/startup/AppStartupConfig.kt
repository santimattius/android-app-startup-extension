package io.github.santimattius.android.startup

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
 *     debugLoggingEnabled       = BuildConfig.DEBUG
 *     strictModeCheckEnabled    = BuildConfig.DEBUG
 *     syncOrderingStrategy      = SyncOrderingStrategy.Topological
 *     asyncInitializerStrategy  = AsyncInitializerStrategy.Validated
 * }
 * ```
 */
class AppStartupConfig private constructor(
    val strictModeCheckEnabled: Boolean,
    val debugLoggingEnabled: Boolean,
    val syncOrderingStrategy: SyncOrderingStrategy,
    val asyncInitializerStrategy: AsyncInitializerStrategy,
) {
    class Builder {
        var strictModeCheckEnabled: Boolean = false
        var debugLoggingEnabled: Boolean = false
        var syncOrderingStrategy: SyncOrderingStrategy = SyncOrderingStrategy.Lazy
        var asyncInitializerStrategy: AsyncInitializerStrategy = AsyncInitializerStrategy.Concurrent

        internal fun build() = AppStartupConfig(
            strictModeCheckEnabled = strictModeCheckEnabled,
            debugLoggingEnabled = debugLoggingEnabled,
            syncOrderingStrategy = syncOrderingStrategy,
            asyncInitializerStrategy = asyncInitializerStrategy,
        )
    }

    internal companion object {
        fun default() = Builder().build()
    }
}
