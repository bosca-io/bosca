package bosca.bible.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StyleRegistryTest {

    @Test
    fun registerAndRetrieveById() {
        val registry = StyleRegistry()
        val style = Style(id = "heading", fontFamily = "Arial")
        registry.register(listOf(style))
        assertEquals(style, registry["heading"])
    }

    @Test
    fun returnNullForUnknownId() {
        val registry = StyleRegistry()
        assertNull(registry["nonexistent"])
    }

    @Test
    fun registerMultipleStyles() {
        val registry = StyleRegistry()
        val style1 = Style(id = "s1")
        val style2 = Style(id = "s2", color = "#FFF")
        registry.register(listOf(style1, style2))
        assertEquals(style1, registry["s1"])
        assertEquals(style2, registry["s2"])
    }

    @Test
    fun laterRegistrationOverwritesSameId() {
        val registry = StyleRegistry()
        val first = Style(id = "dup", color = "#000")
        val second = Style(id = "dup", color = "#FFF")
        registry.register(listOf(first))
        registry.register(listOf(second))
        assertEquals("#FFF", (registry["dup"] as Style).color)
    }

    @Test
    fun registerEmptyListDoesNothing() {
        val registry = StyleRegistry()
        registry.register(emptyList())
        assertNull(registry["anything"])
    }
}
