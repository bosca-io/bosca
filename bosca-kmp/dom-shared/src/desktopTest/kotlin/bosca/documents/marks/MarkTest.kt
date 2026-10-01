package bosca.documents.marks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class MarkTest {

    // --- Bold ---

    @Test
    fun `Bold creation with null attributes`() {
        val bold = Bold()
        assertNull(bold.attributes)
    }

    @Test
    fun `Bold creation with explicit null attributes`() {
        val bold = Bold(attributes = null)
        assertNull(bold.attributes)
    }

    // --- Link ---

    @Test
    fun `Link creation with LinkAttributes containing all fields`() {
        val attrs = LinkAttributes(
            href = "https://example.com",
            url = "https://example.com/page",
            rel = "noopener noreferrer",
            target = "_blank"
        )
        val link = Link(attributes = attrs)

        assertNotNull(link.attributes)
        val linkAttrs = link.attributes as LinkAttributes
        assertEquals("https://example.com", linkAttrs.href)
        assertEquals("https://example.com/page", linkAttrs.url)
        assertEquals("noopener noreferrer", linkAttrs.rel)
        assertEquals("_blank", linkAttrs.target)
    }

    @Test
    fun `Link creation with null attributes`() {
        val link = Link()
        assertNull(link.attributes)
    }

    // --- LinkAttributes ---

    @Test
    fun `LinkAttributes default values are all null`() {
        val attrs = LinkAttributes()
        assertNull(attrs.href)
        assertNull(attrs.url)
        assertNull(attrs.rel)
        assertNull(attrs.target)
    }

    @Test
    fun `LinkAttributes with only href set`() {
        val attrs = LinkAttributes(href = "https://test.com")
        assertEquals("https://test.com", attrs.href)
        assertNull(attrs.url)
        assertNull(attrs.rel)
        assertNull(attrs.target)
    }
}
