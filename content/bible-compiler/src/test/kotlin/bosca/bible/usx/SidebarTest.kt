package bosca.bible.usx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SidebarTest {

    @Test
    fun defaultStyleIsEsb() {
        val attrs = Attributes(emptyMap())
        val sidebar = Sidebar(attrs, null, Position(0))
        assertEquals("esb", sidebar.style)
    }

    @Test
    fun styleFieldPreservation() {
        val attrs = Attributes(mapOf("STYLE" to "custom"))
        val sidebar = Sidebar(attrs, null, Position(0))
        assertEquals("custom", sidebar.style)
    }

    @Test
    fun categoryOptional() {
        val attrs = Attributes(emptyMap())
        val sidebar = Sidebar(attrs, null, Position(0))
        assertNull(sidebar.category)
    }

    @Test
    fun categoryPreserved() {
        val attrs = Attributes(mapOf("CATEGORY" to "study-notes"))
        val sidebar = Sidebar(attrs, null, Position(0))
        assertEquals("study-notes", sidebar.category)
    }

    @Test
    fun htmlClassMatchesStyle() {
        val attrs = Attributes(mapOf("STYLE" to "esb"))
        val sidebar = Sidebar(attrs, null, Position(0))
        assertEquals("esb", sidebar.htmlClass)
    }
}
