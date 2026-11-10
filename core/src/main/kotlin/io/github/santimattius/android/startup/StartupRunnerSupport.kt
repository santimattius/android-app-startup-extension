package io.github.santimattius.android.startup

import android.os.Process

/** Milliseconds from process start to [uptimeMs] (same `uptimeMillis` clock); never negative. */
internal fun processStartOffsetMs(uptimeMs: Long): Long = (uptimeMs - Process.getStartUptimeMillis()).coerceAtLeast(0L)

/**
 * Validates that [component] is a concrete, instantiable class before attempting reflection.
 *
 * Throwing early with a clear message avoids the cryptic [InstantiationException] that the
 * JVM would otherwise produce when [Class.newInstance] is called on an abstract class or interface.
 *
 * @throws StartupExtensionException if [component] is abstract or an interface.
 */
internal fun requireConcreteClass(component: Class<*>) {
    if (component.isInterface ||
        java.lang.reflect.Modifier
            .isAbstract(component.modifiers)
    ) {
        throw StartupExtensionException(
            "Cannot initialize ${component.name}: expected a concrete class " +
                "but got an abstract class or interface.",
        )
    }
}
