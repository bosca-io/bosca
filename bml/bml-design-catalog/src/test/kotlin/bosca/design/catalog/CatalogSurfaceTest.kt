package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogSurfaceTest {
    @Test
    fun `rejected loads retain accepted rows and each outcome issues a sequence`() = runBlocking {
        val surface = CatalogSurface("first")
        val other = CatalogSurface("other")
        assertTrue(surface.rows().isEmpty())
        surface.toggleRejection()
        surface.load()
        assertFalse(surface.loaded)
        assertEquals("error", surface.noticeTone)
        surface.toggleRejection()
        surface.load()
        val accepted = surface.rows()
        assertEquals(CatalogChoiceSearch.options.filterNot { it.disabled }, accepted)
        surface.toggleRejection()
        surface.load()
        assertEquals(accepted, surface.rows())
        assertEquals(3, surface.noticeSequence)
        assertEquals("error", surface.noticeTone)
        surface.clear()
        assertTrue(surface.rows().isEmpty())
        assertEquals("info", surface.noticeTone)
        assertEquals(4, surface.noticeSequence)
        assertEquals(0, other.noticeSequence)
        assertFalse(other.loaded)
    }

    @Test
    fun `long labels and composition options survive session serialization`() = runBlocking {
        val surface = CatalogSurface("example")
        surface.load()
        surface.toggleLongLabels()
        surface.toggleCompact()
        val restored = Json.decodeFromString(CatalogSurface.serializer(), Json.encodeToString(CatalogSurface.serializer(), surface))
        assertEquals(surface.rows(), restored.rows())
        assertEquals(surface.title(), restored.title())
        assertTrue(restored.rows().first().label.contains("<section>"))
        assertTrue(restored.compact)
        assertEquals(1, restored.revision)
        restored.refresh()
        assertEquals(surface.rows(), restored.rows())
        assertEquals(2, restored.noticeSequence)
    }
}
