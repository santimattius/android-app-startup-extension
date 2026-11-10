package io.github.santimattius.android.startup

import io.github.santimattius.android.startup.initializer.StartupSyncInitializer

/**
 * Per-component re-entrant lock coordination extracted from [AppStartupInitializer].
 *
 * Every component gets its own owner/depth/reference entry, all guarded by a single monitor.
 * The monitor is held only while claiming, waiting for, or releasing ownership; callers run
 * `create()` and dependency work outside it. Cross-thread wait cycles are detected before a
 * thread blocks and reported as a [StartupExtensionException].
 */
internal class SyncLockCoordinator {
    private class SyncLockEntry {
        var owner: Thread? = null
        var depth = 0
        var references = 0
    }

    private val syncCoordinator = java.lang.Object()
    private val syncLockEntries = mutableMapOf<Class<out StartupSyncInitializer<*>>, SyncLockEntry>()
    private val syncWaits = mutableMapOf<Thread, Class<out StartupSyncInitializer<*>>>()

    fun acquireSyncLock(component: Class<out StartupSyncInitializer<*>>) {
        val waiter = Thread.currentThread()
        synchronized(syncCoordinator) {
            val entry = syncLockEntries.getOrPut(component) { SyncLockEntry() }
            entry.references++
            try {
                while (true) {
                    val owner = entry.owner
                    when {
                        owner == null -> {
                            entry.owner = waiter
                            entry.depth = 1
                            return
                        }

                        owner === waiter -> {
                            entry.depth++
                            return
                        }

                        else -> {
                            syncWaits[waiter] = component
                            if (createsSyncWaitCycle(waiter, owner)) {
                                syncWaits.remove(waiter)
                                throw StartupExtensionException(
                                    "Cannot initialize ${component.name}. Cross-thread cycle detected.",
                                )
                            }
                            try {
                                syncCoordinator.wait()
                            } catch (interrupted: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw IllegalStateException("Interrupted while waiting for synchronous initialization.", interrupted)
                            } finally {
                                syncWaits.remove(waiter)
                            }
                        }
                    }
                }
            } catch (throwable: Throwable) {
                releaseSyncReference(component, entry)
                throw throwable
            }
        }
    }

    private fun createsSyncWaitCycle(
        waiter: Thread,
        owner: Thread,
    ): Boolean {
        val visited = HashSet<Thread>()
        var current: Thread? = owner
        while (current != null && visited.add(current)) {
            if (current === waiter) return true
            val waitingFor = syncWaits[current] ?: return false
            current = syncLockEntries[waitingFor]?.owner
        }
        return false
    }

    fun releaseSyncLock(component: Class<out StartupSyncInitializer<*>>) {
        val owner = Thread.currentThread()
        synchronized(syncCoordinator) {
            val entry = checkNotNull(syncLockEntries[component]) { "Missing sync lock entry for ${component.name}" }
            check(entry.owner === owner) { "Thread does not own sync lock for ${component.name}" }
            entry.depth--
            if (entry.depth == 0) {
                entry.owner = null
                syncCoordinator.notifyAll()
            }
            releaseSyncReference(component, entry)
        }
    }

    private fun releaseSyncReference(
        component: Class<out StartupSyncInitializer<*>>,
        entry: SyncLockEntry,
    ) {
        check(entry.references > 0) { "Missing sync lock reference for ${component.name}" }
        entry.references--
        if (entry.references == 0) {
            check(entry.owner == null) { "Sync lock entry released while ${component.name} is owned" }
            syncLockEntries.remove(component)
        }
    }
}
