package io.github.santimattius.android.startup.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupTraceNamesTest {

    private val phases = listOf("create", "deps", "permitWait", "instantiate", "deferred::awaitFirstFrame", "onDemand")

    @Test
    fun `phase and fqn produce the wildcard-friendly name`() {
        assertEquals("Startup::create::com.acme.Foo", StartupTraceNames.of("create", "com.acme.Foo"))
    }

    @Test
    fun `node name without phase has no phase segment`() {
        assertEquals("Startup::com.acme.Foo", StartupTraceNames.of(null, "com.acme.Foo"))
    }

    @Test
    fun `phase only name omits the fqn suffix`() {
        assertEquals("Startup::discover", StartupTraceNames.of("discover", null))
    }

    @Test
    fun `long name keeps the prefix and the fqn tail within 127 chars`() {
        val fqn = "com." + "segment.".repeat(30) + "TailInitializer"

        val name = StartupTraceNames.of("create", fqn)

        assertEquals(127, name.length)
        assertTrue(name.startsWith("Startup::create::.."))
        assertTrue(name.endsWith("TailInitializer"))
    }

    @Test
    fun `over-budget fqn keeps the phase prefix for every phase`() {
        val fqn = "x".repeat(300) + ".Tail"

        phases.forEach { phase ->
            val name = StartupTraceNames.of(phase, fqn)
            assertTrue("$phase -> ${name.length}", name.length <= 127)
            assertTrue(name, name.startsWith("Startup::$phase::"))
            assertTrue(name, name.endsWith(".Tail"))
        }
        assertTrue(StartupTraceNames.of(null, fqn).let { it.length <= 127 && it.startsWith("Startup::") })
    }

    @Test
    fun `name at exactly 127 chars is not shortened`() {
        val prefix = "Startup::create::"
        val fqn = "a".repeat(127 - prefix.length)

        assertEquals(prefix + fqn, StartupTraceNames.of("create", fqn))
    }
}
