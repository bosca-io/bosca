package bosca.design.catalog

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogViewsTest {
    @Test
    fun `search filters real examples and rejection keeps accepted results separate from drafts`(): Unit = runBlocking {
        val views = CatalogViews("first")
        val other = CatalogViews("other")
        views.search("  LAYOUT  ", "all")
        assertEquals(listOf("card", "panel", "page-shell", "page-heading", "section"), views.results().map { it.value })
        views.toggleRejection()
        views.search("Search <draft>", "control")
        assertEquals("Search <draft>", views.query)
        assertEquals("control", views.mode)
        assertEquals("  LAYOUT  ", views.resultQuery)
        assertEquals("all", views.resultMode)
        assertEquals(5, views.results().size)
        assertEquals("error", views.noticeTone)
        assertEquals(2, views.noticeSequence)
        views.toggleRejection()
        views.search("", "overlay")
        assertEquals(listOf("dialog", "popover"), views.results().map { it.value })
        views.search("not-present", "all")
        assertTrue(views.results().isEmpty())
        views.search("", "all")
        assertTrue(views.results().none { it.disabled })
        assertEquals(0, other.noticeSequence)
        assertEquals("", other.query)
    }

    @Test
    fun `controlled values tab revisions and availability survive session serialization`(): Unit = runBlocking {
        val views = CatalogViews("views")
        assertFailsWith<IllegalArgumentException> { views.search("query", "missing") }
        views.toggleDisabled()
        assertFailsWith<IllegalStateException> { views.search("query", "all") }
        views.toggleDisabled()
        views.toggleVisible()
        assertFailsWith<IllegalStateException> { views.search("query", "all") }
        views.chooseView("Source")
        views.changeTabs()
        assertEquals(listOf("Preview", "Notes"), views.tabs())
        assertEquals("Preview", views.selected)
        assertFailsWith<IllegalArgumentException> { views.chooseView("Source") }
        views.replace()
        views.refresh()
        val restored = Json.decodeFromString(CatalogViews.serializer(), Json.encodeToString(CatalogViews.serializer(), views))
        assertEquals(1, restored.revision)
        assertEquals("Notes", restored.selected)
        assertEquals("layout", restored.query)
        assertEquals("", restored.resultQuery)
        assertEquals(false, restored.visible)
        assertEquals("info", restored.noticeTone)
        restored.changeTabs()
        assertEquals(listOf("Preview", "Details", "Source"), restored.tabs())
        assertEquals(2, restored.revision)
        restored.replace()
        assertEquals("control", restored.query)
        assertEquals("Details", restored.selected)
    }
}
