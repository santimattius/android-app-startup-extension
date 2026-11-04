package com.santimattius.android.sample.usecases

import com.santimattius.android.sample.AsyncTestInitializer
import com.santimattius.android.sample.DependencyOnlyInitializer
import com.santimattius.android.sample.SyncTestInitializer
import io.github.santimattius.android.startup.AppStartupInitializer

/** Use case C (basics): on-demand initialization, caching, eager flags and dependency ordering. */
object OnDemandCases {

    val basic: List<DemoCase> = listOf(
        DemoCase(
            "sync", "doInitialize (sync) and initializeComponent",
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
            "async", "doInitialize (async)",
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
            "transient", "Retained vs retainAfterStartup = false",
            "A transient initializer is released after create(), so requesting it twice runs " +
                "create() twice. The retained one above would run once.",
        ) { startup ->
            val sync = List(2) { startup.doInitialize<String>(TransientSyncDemo::class.java) }
            val async = List(2) { startup.doInitialize<String>(TransientAsyncDemo::class.java) }
            listOf("sync x2 -> $sync", "async x2 -> $async")
        },
        DemoCase(
            "eager", "isEagerlySyncInitialized / isEagerlyAsyncInitialized",
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
            "deps", "dependencies() and syncDependencies()",
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

    val all: List<DemoCase> = basic
}
