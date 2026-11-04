package com.santimattius.android.sample.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    @Test
    fun `every route reaches Home through its parents`() {
        Route.entries.forEach { route ->
            var current = route
            var hops = 0
            while (current.parent != null) {
                current = current.parent
                check(++hops < Route.entries.size) { "cycle at $route" }
            }
            assertEquals(Route.Home, current)
        }
        assertNull(Route.Home.parent)
    }

    @Test
    fun `hub lists only direct children and never itself or home`() {
        assertTrue(Route.Observability in Route.useCases)
        assertTrue(Route.Hub !in Route.useCases && Route.Home !in Route.useCases)
    }

    @Test
    fun `unknown or missing saved names fall back to Home`() {
        assertEquals(Route.Home, Route.fromName(null))
        assertEquals(Route.Home, Route.fromName("Nope"))
        assertEquals(Route.Observability, Route.fromName("Observability"))
    }
}
