package io.github.santimattius.android.startup

/**
 * Awaits the completion of all start jobs initiated by the [AppStartupInitializer].
 *
 * This function suspends until all asynchronous tasks started by the [AppStartupInitializer]
 * during its initialization phase have finished.  These "start jobs" typically represent
 * operations like preloading data, initializing libraries, or performing other setup tasks
 * that can be done in the background.
 *
 * It delegates the actual waiting to the [coroutinesEngine], which is responsible for
 * managing the lifecycle and execution of these jobs.
 *
 * @throws Exception if any of the start jobs throws an exception. This will be the exception from the first failed job.
 *
 * Example Usage:
 *
 * ```kotlin
 * val appStartupInitializer = AppStartupInitializer(coroutinesEngine)
 * // ... initialize the initializer, adding start jobs ...
 * appStartupInitializer.initialize()
 * // Wait for all start jobs to complete before proceeding.
 * appStartupInitializer.awaitAllStartJobs()
 * // Now it is safe to assume all initialization tasks are done
 * ```
 */
suspend fun AppStartupInitializer.awaitAllStartJobs() {
    coroutinesEngine.awaitAllStartJobs()
}

/**
 * Executes a given block of code after all App Startup jobs have been completed.
 *
 * This function ensures that all startup tasks registered with the [AppStartupInitializer]
 * have finished their execution before running the provided [block]. It's useful for performing
 * operations that depend on the successful completion of all initializations.
 *
 * @param block The suspend function to be executed after all App Startup jobs are finished.
 *              This function receives the [AppStartupInitializer] instance as a parameter,
 *              allowing access to its context and functionalities.
 * @throws Exception if any of the startup jobs fails. The specific exception thrown will depend
 *                    on the failure of the corresponding startup job.
 * @see AppStartupInitializer
 * @see awaitAllStartJobs
 */
suspend fun AppStartupInitializer.onAppStartupLaunched(block: suspend (AppStartupInitializer) -> Unit) {
    awaitAllStartJobs()
    block(this)
}

/**
 * Checks if all jobs started by the [AppStartupInitializer] are completed.
 *
 * This function iterates through the list of start jobs managed by the [AppStartupInitializer]'s
 * [coroutinesEngine] and determines if any of them are still active.
 * A job is considered "done" when it is no longer active (i.e., it has completed or been cancelled).
 *
 * @return `true` if all started jobs are done (not active), `false` otherwise.
 *
 * @see kotlinx.coroutines.Job.isActive
 * @see AppStartupInitializer
 * @see CoroutinesEngine
 */
fun AppStartupInitializer.isAllStartedJobsDone(): Boolean {
    return coroutinesEngine.startJobs.none { it.isActive }
}