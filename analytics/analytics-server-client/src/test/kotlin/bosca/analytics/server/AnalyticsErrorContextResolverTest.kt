package bosca.analytics.server

import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyticsErrorContextResolverTest {

    @Test
    fun `resolveAmbient merges ambient and explicit context with explicit winning`() {
        val ambient = mapOf("a" to 1, "b" to 2, "c" to 3)
        val explicit = mapOf("b" to 22, "d" to 4)
        val merged = AnalyticsErrorContextResolver.resolveAmbient(ambient, explicit)
        assertEquals(mapOf("a" to 1, "b" to 22, "c" to 3, "d" to 4), merged)
    }

    @Test
    fun `resolve fold-order is request then ambient then explicit`() {
        val request = mapOf("k" to "request", "rk" to "r")
        val ambient = mapOf("k" to "ambient", "ak" to "a")
        val explicit = mapOf("k" to "explicit", "ek" to "e")
        val merged = AnalyticsErrorContextResolver.resolve(
            call = null,
            ambient = ambient,
            explicit = explicit,
            requestContext = request,
        )
        assertEquals("explicit", merged["k"])
        assertEquals("r", merged["rk"])
        assertEquals("a", merged["ak"])
        assertEquals("e", merged["ek"])
    }

    @Test
    fun `null call is handled gracefully`() {
        val merged = AnalyticsErrorContextResolver.resolve(
            call = null,
            ambient = emptyMap(),
            explicit = mapOf("only" to "value"),
            requestContext = emptyMap(),
        )
        assertEquals(mapOf("only" to "value"), merged)
    }
}
