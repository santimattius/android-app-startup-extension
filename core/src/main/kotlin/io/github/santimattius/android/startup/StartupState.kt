package io.github.santimattius.android.startup

/**
 * Represents the lifecycle state of the application startup process managed by
 * [AppStartupInitializer].
 *
 * States progress in order on the happy path:
 * ```
 * IDLE → IN_PROGRESS → COMPLETED
 * ```
 * or, if any initializer job throws:
 * ```
 * IDLE → IN_PROGRESS → ERROR
 * ```
 *
 * With [kotlinx.coroutines.SupervisorJob] semantics, sibling jobs continue running even after
 * one fails. [ERROR] is set when [AppStartupInitializer.awaitAllStartJobs] encounters the first
 * failed job via [kotlinx.coroutines.awaitAll].
 *
 * Observe via [AppStartupInitializer.startupState].
 */
enum class StartupState {
    /** No startup jobs have been launched yet. */
    IDLE,

    /** At least one startup job is currently running. */
    IN_PROGRESS,

    /** All awaited startup jobs completed without throwing. */
    COMPLETED,

    /**
     * At least one startup job threw a non-cancellation exception.
     * Other sibling jobs may still complete thanks to [kotlinx.coroutines.SupervisorJob].
     */
    ERROR,
}
