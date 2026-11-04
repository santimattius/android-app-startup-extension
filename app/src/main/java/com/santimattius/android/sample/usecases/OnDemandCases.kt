package com.santimattius.android.sample.usecases

import com.santimattius.android.sample.AsyncTestInitializer
import com.santimattius.android.sample.DependencyOnlyInitializer
import com.santimattius.android.sample.SyncTestInitializer

/** Use case C (basics): on-demand initialization, caching, eager flags and dependency ordering. */
object OnDemandCases {
    val basic: List<DemoCase> =
        listOf(
            DemoCase(
                "sync",
                "doInitialize (sync) and initializeComponent",
                "Both create RetainedSyncDemo on the calling thread. It is retained, so create() runs " +
                    "at most once and the second call returns the same instance.",
            ) { startup ->
                val first = startup.doInitialize<String>(RetainedSyncDemo::class.java)
                val second = startup.initializeComponent(RetainedSyncDemo::class.java)
                listOf(
                    "doInitialize -> $first",
                    "initializeComponent -> $second",
                    "same instance: ${first === second}",
                    "create() calls so far: ${DemoCounters.count(RetainedSyncDemo::class.java)}",
                )
            },
            DemoCase(
                "async",
                "doInitialize (async)",
                "Suspends until the async initializer finishes; the second call hits the cache.",
            ) { startup ->
                val first = startup.doInitialize<String>(RetainedAsyncDemo::class.java)
                val second = startup.doInitialize<String>(RetainedAsyncDemo::class.java)
                listOf(
                    "first -> $first",
                    "second -> $second",
                    "create() calls so far: ${DemoCounters.count(RetainedAsyncDemo::class.java)}",
                )
            },
            DemoCase(
                "transient",
                "Retained vs retainAfterStartup = false",
                "A transient initializer is released after create(), so requesting it twice runs " +
                    "create() twice. The retained one above would run once.",
            ) { startup ->
                val sync = List(2) { startup.doInitialize<String>(TransientSyncDemo::class.java) }
                val async = List(2) { startup.doInitialize<String>(TransientAsyncDemo::class.java) }
                listOf("sync x2 -> $sync", "async x2 -> $async")
            },
            DemoCase(
                "eager",
                "isEagerlySyncInitialized / isEagerlyAsyncInitialized",
                "True only for initializers discovered from the manifest at startup. On-demand and " +
                    "dependency-only classes are not eager.",
            ) { startup ->
                listOf(
                    "SyncTestInitializer (manifest): " + startup.isEagerlySyncInitialized(SyncTestInitializer::class.java),
                    "DependencyOnlyInitializer (dependency only): " +
                        startup.isEagerlySyncInitialized(DependencyOnlyInitializer::class.java),
                    "RetainedSyncDemo (on demand): " + startup.isEagerlySyncInitialized(RetainedSyncDemo::class.java),
                    "AsyncTestInitializer (manifest): " + startup.isEagerlyAsyncInitialized(AsyncTestInitializer::class.java),
                    "RetainedAsyncDemo (on demand): " + startup.isEagerlyAsyncInitialized(RetainedAsyncDemo::class.java),
                )
            },
            DemoCase(
                "deps",
                "dependencies() and syncDependencies()",
                "Creation order proves each dependency runs before its dependent: sync -> sync, " +
                    "async -> async and async -> sync (syncDependencies() creates the sync class first).",
            ) { startup ->
                DemoCounters.drainEvents()
                startup.doInitialize<Unit>(SyncParentDemo::class.java)
                val syncOrder = DemoCounters.drainEvents()
                startup.doInitialize<Unit>(AsyncParentDemo::class.java)
                val asyncOrder = DemoCounters.drainEvents()
                startup.doInitialize<Unit>(AsyncNeedsSyncDemo::class.java)
                val crossOrder = DemoCounters.drainEvents()
                listOf("sync -> sync: $syncOrder", "async -> async: $asyncOrder", "async -> sync: $crossOrder")
            },
        )

    val advanced: List<DemoCase> =
        listOf(
            DemoCase(
                "dispatcher",
                "Per-initializer dispatcher() override",
                "Metrics report the dispatcher and thread create() really ran on: IO, a dedicated " +
                    "single thread, and the library default (Dispatchers.Default).",
            ) { startup ->
                listOf(IoDispatcherDemo::class.java, SingleThreadDispatcherDemo::class.java, TransientAsyncDemo::class.java)
                    .flatMap { cls ->
                        startup.doInitialize<Any>(cls)
                        val m = startup.lastMetricOf(cls)
                        listOf("${cls.simpleName}: ${m?.dispatcherName} on ${m?.threadName}")
                    }
            },
            DemoCase(
                "priority",
                "priority() values",
                "The metric carries the declared priority. On-demand calls are never gated by the " +
                    "first frame: DEFERRED only delays eager startup roots.",
            ) { startup ->
                listOf(CriticalPriorityDemo::class.java, DeferredPriorityDemo::class.java).map { cls ->
                    val started = System.nanoTime()
                    startup.doInitialize<Any>(cls)
                    val ms = (System.nanoTime() - started) / 1_000_000
                    "${cls.simpleName}: priority=${startup.lastMetricOf(cls)?.priority} returned after $ms ms"
                }
            },
            DemoCase(
                "failure",
                "Failure reporting and recovery",
                "On-demand async requests are serialized by the library. A failed request surfaces as " +
                    "StartupExtensionException and in its metric; a later request can still succeed.",
            ) { startup ->
                val failure = runCatching { startup.doInitialize<Any>(FailingAsyncDemo::class.java) }.exceptionOrNull()
                val bm = startup.lastMetricOf(FailingAsyncDemo::class.java)
                val next = startup.doInitialize<String>(TransientAsyncDemo::class.java)
                listOf(
                    "failing threw: ${failure?.let { it::class.simpleName }} (cause: ${failure?.cause?.message})",
                    "failing metric: success=${bm?.success} error=${bm?.errorType}: ${bm?.errorMessage}",
                    "next serialized request -> $next",
                )
            },
            DemoCase(
                "exceptions",
                "StartupExtensionException",
                "Thrown for an abstract class, and wrapping the cycle error for circular dependencies.",
            ) { startup ->
                fun describe(
                    label: String,
                    block: () -> Unit,
                ) = runCatching(block).fold(
                    { "$label: no exception" },
                    { "$label: ${it::class.simpleName} / root: ${generateSequence(it) { t -> t.cause }.last().message}" },
                )
                listOf(
                    describe("abstract sync") { startup.doInitialize<Any>(AbstractSyncDemo::class.java) },
                    describe("sync cycle") { startup.doInitialize<Any>(CycleASyncDemo::class.java) },
                    runCatching { startup.doInitialize<Any>(AbstractAsyncDemo::class.java) }
                        .exceptionOrNull()
                        .let { "abstract async: ${it?.let { e -> e::class.simpleName }}" },
                    runCatching { startup.doInitialize<Any>(CycleAAsyncDemo::class.java) }
                        .exceptionOrNull()
                        .let {
                            "async cycle: ${it?.let { e -> e::class.simpleName }} / root: " +
                                it?.let { e -> generateSequence(e) { t -> t.cause }.last().message }
                        },
                )
            },
        )

    val all: List<DemoCase> = basic + advanced
}
