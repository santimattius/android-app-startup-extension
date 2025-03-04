package io.github.santimattius.android.startup

suspend fun AppInitializer.awaitAllStartJobs() {
    coroutinesEngine.awaitAllStartJobs()
}

/**
 * Wait for Starting coroutines jobs to run block code
 *
 * @param block
 */
suspend fun AppInitializer.onAppStartupLaunched(block: suspend (AppInitializer) -> Unit) {
    awaitAllStartJobs()
    block(this)
}

/**
 * Indicates if all start jobs have been done
 */
fun AppInitializer.isAllStartedJobsDone(): Boolean {
    return coroutinesEngine.startJobs.none { it.isActive }
}