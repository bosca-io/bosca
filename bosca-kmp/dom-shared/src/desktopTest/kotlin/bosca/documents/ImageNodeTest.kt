package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageNodeTest {

    @Test
    fun `ImageNode preserves all attribute fields`() {
        val attrs = ImageAttributes(
            classes = "img-class",
            alt = "A photo",
            src = "https://example.com/img.png",
            title = "Photo title",
            metadataId = "meta-123"
        )
        val node = ImageNode(attributes = attrs)
        assertEquals("img-class", node.attributes.classes)
        assertEquals("A photo", node.attributes.alt)
        assertEquals("https://example.com/img.png", node.attributes.src)
        assertEquals("Photo title", node.attributes.title)
        assertEquals("meta-123", node.attributes.metadataId)
    }

    @Test
    fun `ImageNode default content is empty`() {
        val node = ImageNode(attributes = ImageAttributes())
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `ImageNode default marks is empty`() {
        val node = ImageNode(attributes = ImageAttributes())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `ImageAttributes default fields are null`() {
        val attrs = ImageAttributes()
        assertNull(attrs.classes)
        assertNull(attrs.alt)
        assertNull(attrs.src)
        assertNull(attrs.title)
        assertNull(attrs.metadataId)
    }

    @Test
    fun `ImageAttributes withClasses creates copy with new classes`() {
        val original = ImageAttributes(src = "img.png", alt = "test")
        val modified = original.withClasses("new-class")
        assertEquals("new-class", modified.classes)
        assertEquals("img.png", modified.src)
        assertEquals("test", modified.alt)
        assertNull(original.classes)
    }

    @Test
    fun `ImageNode preserves marks`() {
        val node = ImageNode(attributes = ImageAttributes(), marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }
}
