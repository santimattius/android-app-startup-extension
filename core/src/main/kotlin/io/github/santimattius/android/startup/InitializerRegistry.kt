package io.github.santimattius.android.startup

import java.util.concurrent.ConcurrentHashMap

/**
 * Sole owner of reflective initializer construction, so each class is instantiated once per startup.
 *
 * Graph passes (topological sort, root validation, effective priorities) use [peek], which creates
 * and caches without consuming; execution uses [take], which hands the cached instance over and
 * forgets it. Returns [Any] so callers keep their own casts (and their failure semantics).
 *
 * [peek] is atomic: concurrent peeks of the same class construct exactly one instance. An instance
 * that is peeked but never taken (e.g. a class that is only validated, or startup that aborts) stays
 * cached for the lifetime of this registry; the registry is owned by a single
 * `AppStartupInitializer`, so that is bounded by one startup's initializer set.
 */
internal class InitializerRegistry {

    private val instances = ConcurrentHashMap<Class<*>, Any>()

    fun peek(cls: Class<*>): Any = instances.computeIfAbsent(cls) { newInstance(cls) }

    fun take(cls: Class<*>): Any = instances.remove(cls) ?: newInstance(cls)

    private fun newInstance(cls: Class<*>): Any = cls.getDeclaredConstructor().newInstance()
}
