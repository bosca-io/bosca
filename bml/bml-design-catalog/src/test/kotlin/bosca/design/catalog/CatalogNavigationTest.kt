package bosca.design.catalog

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogNavigationTest {
    @Test
    fun `destination revisions keep one selected item and state remains isolated`() {
        val navigation = CatalogNavigation("first")
        val other = CatalogNavigation("other")
        assertEquals("/navigation", navigation.items().single { it.selected }.href)
        navigation.changeCurrent()
        navigation.toggleLong()
        navigation.replace()
        assertEquals(3, navigation.revision)
        assertEquals(4, navigation.items().size)
        val selected = navigation.items().single { it.selected }
        assertEquals("/components/layout", selected.href)
        assertTrue(selected.label.contains("long current"))
        assertEquals(8, other.items().size)
        assertEquals("/navigation", other.items().single { it.selected }.href)
        navigation.toggleNarrow()
        navigation.toggleVisible()
        navigation.refresh()
        val restored = Json.decodeFromString(CatalogNavigation.serializer(), Json.encodeToString(CatalogNavigation.serializer(), navigation))
        assertEquals(navigation.items(), restored.items())
        assertEquals(3, restored.revision)
        assertEquals(1, restored.noticeSequence)
        assertFalse(restored.narrow)
        assertFalse(restored.visible)
        assertEquals(0, other.noticeSequence)
    }

    @Test
    fun `overflow examples point to distinct actual export references`() {
        val examples = CatalogNavigation.examples()
        assertEquals(15, examples.size)
        assertEquals(examples.size, examples.map { it.href }.distinct().size)
        assertEquals("/components/overlays#ref-popover", examples.single { it.selected }.href)
        assertTrue(examples.all { it.href.startsWith("/components/") && "#ref-" in it.href })
        assertTrue(examples.none { "unavailable" in it.href })
    }
}
