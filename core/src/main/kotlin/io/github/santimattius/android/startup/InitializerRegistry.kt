package io.github.santimattius.android.startup

import java.util.concurrent.ConcurrentHashMap

/**
 * Sole owner of reflective initializer construction, so each class is instantiated once per startup.
 *
 * Graph passes (topological sort, root validation, effective priorities) use [peek], which creates
 * and caches without consuming; execution uses [take], which hands the cached instance over and
 * forgets it. Returns [Any] so callers keep their own casts (and their failure semantics).
 */
internal class InitializerRegistry {

    private val instances = ConcurrentHashMap<Class<*>, Any>()

    fun peek(cls: Class<*>): Any = instances.getOrPut(cls) { newInstance(cls) }

    fun take(cls: Class<*>): Any = instances.remove(cls) ?: newInstance(cls)

    private fun newInstance(cls: Class<*>): Any = cls.getDeclaredConstructor().newInstance()
}
