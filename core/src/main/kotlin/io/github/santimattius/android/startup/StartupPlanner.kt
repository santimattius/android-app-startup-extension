package io.github.santimattius.android.startup

import android.util.Log
import io.github.santimattius.android.startup.initializer.StartupAsyncInitializer
import io.github.santimattius.android.startup.initializer.StartupSyncInitializer
import io.github.santimattius.android.startup.trace.StartupTraceNames
import io.github.santimattius.android.startup.trace.StartupTracer
import io.github.santimattius.android.startup.trace.mark
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure planning logic extracted from [AppStartupInitializer]: dependency ordering, graph
 * validation and effective-priority resolution. It never launches work; it only reads
 * initializer instances through [registry].
 */
internal class StartupPlanner(
    private val registry: InitializerRegistry,
    private val tracer: StartupTracer,
) {
    /**
     * Guards the priority-inversion warning so it is emitted AT MOST ONCE per startup
     * (per initializer instance), regardless of how many DEFERRED nodes are clamped.
     */
    private val inversionWarned = AtomicBoolean(false)

    /**
     * Sorts [discovered] sync initializers using Kahn's algorithm so that every dependency
     * appears before the nodes that depend on it.
     *
     * Cycle detection is a by-product of Kahn's: if not all nodes are processed the remaining
     * ones must form a cycle. The exception is thrown before any [StartupSyncInitializer.create]
     * is called, making the failure state clean and easy to diagnose.
     *
     * @throws StartupExtensionException if a cycle is found, naming all participating nodes.
     */
    fun topologicalSort(discovered: Set<Class<out StartupSyncInitializer<*>>>): List<Class<out StartupSyncInitializer<*>>> {
        val inDegree = mutableMapOf<Class<out StartupSyncInitializer<*>>, Int>()
        val adjacency = mutableMapOf<Class<out StartupSyncInitializer<*>>, MutableList<Class<out StartupSyncInitializer<*>>>>()

        discovered.forEach { cls ->
            inDegree.getOrPut(cls) { 0 }
            adjacency.getOrPut(cls) { mutableListOf() }

            registry
                .peek(cls)
                .let { it as StartupSyncInitializer<*> }
                .dependencies()
                .filter { it in discovered }
                .forEach { dep ->
                    adjacency.getOrPut(dep) { mutableListOf() }.add(cls)
                    inDegree[cls] = (inDegree[cls] ?: 0) + 1
                    inDegree.getOrPut(dep) { 0 }
                }
        }

        val queue = ArrayDeque<Class<out StartupSyncInitializer<*>>>()
        inDegree.filterValues { it == 0 }.keys.forEach { queue.add(it) }

        val sorted = mutableListOf<Class<out StartupSyncInitializer<*>>>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sorted.add(current)
            adjacency[current]?.forEach { neighbor ->
                val newDegree = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDegree
                if (newDegree == 0) queue.add(neighbor)
            }
        }

        if (sorted.size != discovered.size) {
            val cycleNodes = (discovered - sorted.toSet()).joinToString(", ") { it.simpleName }
            throw StartupExtensionException("Cycle detected in sync initializers: [$cycleNodes]")
        }

        return sorted
    }

    /**
     * Validates the async-to-async dependency graph (built from [StartupAsyncInitializer.dependencies]
     * only — [StartupAsyncInitializer.syncDependencies] are excluded because all sync initializers
     * discovered in the manifest have already completed before this is called) and returns the
     * **root** nodes: async initializers that no other async initializer depends on. When
     * [includeTransitiveDependencies] is true, validation follows dependencies absent from manifest
     * metadata too; returned roots remain restricted to [discovered].
     *
     * Cycle detection uses Kahn's algorithm as a by-product: if not all nodes are processed,
     * the remaining ones form a cycle. The exception is thrown before any coroutine is launched
     * or any [StartupAsyncInitializer.create] is called.
     *
     * Launching only the roots is sufficient — each root coroutine resolves its transitive
     * async dependencies recursively via [doAsyncInitialize], eliminating redundant coroutine launches.
     *
     * @throws StartupExtensionException if a cycle is found, naming all participating nodes.
     */
    fun asyncValidateAndGetRoots(
        discovered: Set<Class<out StartupAsyncInitializer<*>>>,
        includeTransitiveDependencies: Boolean = false,
    ): Set<Class<out StartupAsyncInitializer<*>>> {
        val graphNodes = mutableSetOf<Class<out StartupAsyncInitializer<*>>>()
        if (includeTransitiveDependencies) {
            val toVisit = ArrayDeque(discovered)
            while (toVisit.isNotEmpty()) {
                val current = toVisit.removeFirst()
                if (!graphNodes.add(current)) continue
                val initializer = registry.peek(current) as StartupAsyncInitializer<*>
                toVisit.addAll(initializer.dependencies())
            }
        } else {
            graphNodes.addAll(discovered)
        }

        val inDegree = mutableMapOf<Class<out StartupAsyncInitializer<*>>, Int>()
        val adjacency = mutableMapOf<Class<out StartupAsyncInitializer<*>>, MutableList<Class<out StartupAsyncInitializer<*>>>>()
        val allDeclaredDependencies = mutableSetOf<Class<out StartupAsyncInitializer<*>>>()

        graphNodes.forEach { cls ->
            inDegree.getOrPut(cls) { 0 }
            adjacency.getOrPut(cls) { mutableListOf() }

            registry
                .peek(cls)
                .let { it as StartupAsyncInitializer<*> }
                .dependencies()
                .filter { it in graphNodes }
                .forEach { dep ->
                    allDeclaredDependencies.add(dep)
                    adjacency.getOrPut(dep) { mutableListOf() }.add(cls)
                    inDegree[cls] = (inDegree[cls] ?: 0) + 1
                    inDegree.getOrPut(dep) { 0 }
                }
        }

        val queue = ArrayDeque<Class<out StartupAsyncInitializer<*>>>()
        inDegree.filterValues { it == 0 }.keys.forEach { queue.add(it) }

        val processed = mutableListOf<Class<out StartupAsyncInitializer<*>>>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            processed.add(current)
            adjacency[current]?.forEach { neighbor ->
                val newDegree = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDegree
                if (newDegree == 0) queue.add(neighbor)
            }
        }

        if (processed.size != graphNodes.size) {
            val cycleNodes = (graphNodes - processed.toSet()).joinToString(", ") { it.simpleName }
            throw StartupExtensionException("Cycle detected in async initializers: [$cycleNodes]")
        }

        return discovered - allDeclaredDependencies
    }

    /**
     * Computes the **effective** [StartupPriority] of every discovered async initializer, resolving
     * priority inversions (ADR-6, spec "Priority inversion clamp and warn").
     *
     * A priority inversion occurs when an eager ([StartupPriority.NORMAL]/[StartupPriority.CRITICAL])
     * initializer depends — directly or transitively — on a [StartupPriority.DEFERRED] one. Deferring
     * that dependency past the first frame would strand the eager dependent, so its EFFECTIVE priority
     * is clamped **up** to the most-eager (minimum ordinal) declared priority among its transitive
     * dependents, and a one-time warning is always emitted via `Log.w` (not gated by
     * `debugLoggingEnabled`, since a priority inversion is a graph misconfiguration developers
     * must see). A DEFERRED node with no eager dependent keeps its declared priority.
     *
     * This is a pure read-only pass: it instantiates each discovered node once (the same reflection
     * the graph validators already use), builds a reverse-adjacency map (dependency -> dependents),
     * and runs a visited-guarded DFS over the reverse edges so self- and mutual-dependencies cannot
     * loop. It launches nothing.
     *
     * @param discovered the set of discovered async initializer classes to classify.
     * @return a map from each discovered class to its effective [StartupPriority].
     */
    fun computeEffectivePriorities(
        discovered: Set<Class<out StartupAsyncInitializer<*>>>,
    ): Map<Class<out StartupAsyncInitializer<*>>, StartupPriority> {
        if (discovered.isEmpty()) return emptyMap()

        val declared = mutableMapOf<Class<out StartupAsyncInitializer<*>>, StartupPriority>()
        val dependents = mutableMapOf<Class<out StartupAsyncInitializer<*>>, MutableList<Class<out StartupAsyncInitializer<*>>>>()

        discovered.forEach { cls ->
            val initializer = registry.peek(cls) as StartupAsyncInitializer<*>
            declared[cls] = initializer.priority()
            initializer
                .dependencies()
                .filter { it in discovered }
                .forEach { dep -> dependents.getOrPut(dep) { mutableListOf() }.add(cls) }
        }

        val effective = declared.toMutableMap()

        declared.filterValues { it == StartupPriority.DEFERRED }.keys.forEach { deferredNode ->
            var clampTarget: StartupPriority? = null
            val visited = mutableSetOf<Class<out StartupAsyncInitializer<*>>>()
            val stack = ArrayDeque(dependents[deferredNode].orEmpty())
            while (stack.isNotEmpty()) {
                val dependent = stack.removeLast()
                if (!visited.add(dependent)) continue // visited-guard => cycle-safe

                val dependentPriority = declared[dependent] ?: StartupPriority.NORMAL
                if (dependentPriority != StartupPriority.DEFERRED &&
                    (clampTarget == null || dependentPriority.ordinal < clampTarget.ordinal)
                ) {
                    clampTarget = dependentPriority
                }
                dependents[dependent]?.let(stack::addAll)
            }

            clampTarget?.let { target ->
                effective[deferredNode] = target
                warnPriorityInversionOnce(deferredNode, target)
            }
        }

        return effective
    }

    /**
     * Emits a single priority-inversion warning per startup (per initializer instance). The
     * warning always surfaces via `Log.w` (not gated by `debugLoggingEnabled`). The
     * [AtomicBoolean] guard keeps it to one message even when several DEFERRED nodes are clamped.
     */
    private fun warnPriorityInversionOnce(
        node: Class<out StartupAsyncInitializer<*>>,
        clampedTo: StartupPriority,
    ) {
        if (!inversionWarned.compareAndSet(false, true)) return
        tracer.mark { StartupTraceNames.of("marker::priorityInversion", node.name) }

        Log.w(
            STARTUP_LOG_TAG,
            "Detected startup priority inversion: DEFERRED initializer ${node.simpleName} is required " +
                "by an eager ($clampedTo) initializer, so its effective priority is clamped up to " +
                "$clampedTo and it will launch eagerly (not after the first frame). Review the " +
                "dependency graph or the priority() overrides to remove the inversion.",
        )
    }

    private companion object {
        private const val STARTUP_LOG_TAG = "StartupLogger"
    }
}
