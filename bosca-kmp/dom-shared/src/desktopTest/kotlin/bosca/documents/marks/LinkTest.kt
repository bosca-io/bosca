package bosca.documents.marks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkTest {

    @Test
    fun `Link default creation has null attributes`() {
        val mark = Link()
        assertNull(mark.attributes)
    }

    @Test
    fun `Link is a Mark`() {
        val mark = Link()
        assertTrue(mark is Mark)
    }

    @Test
    fun `Link preserves LinkAttributes with all fields`() {
        val attrs = LinkAttributes(
            href = "https://example.com",
            url = "https://example.com/page",
            rel = "noopener",
            target = "_blank"
        )
        val mark = Link(attributes = attrs)
        assertEquals("https://example.com", mark.attributes?.href)
        assertEquals("https://example.com/page", mark.attributes?.url)
        assertEquals("noopener", mark.attributes?.rel)
        assertEquals("_blank", mark.attributes?.target)
    }

    @Test
    fun `LinkAttributes default fields are null`() {
        val attrs = LinkAttributes()
        assertNull(attrs.href)
        assertNull(attrs.url)
        assertNull(attrs.rel)
        assertNull(attrs.target)
    }

    @Test
    fun `LinkAttributes is a MarkAttributes`() {
        val attrs = LinkAttributes()
        assertTrue(attrs is MarkAttributes)
    }
}
