package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerNodeTest {

    @Test
    fun `ContainerNode preserves all attribute fields`() {
        val attrs = ContainerAttributes(
            classes = "container-class",
            name = "my-container",
            renderer = "custom-renderer"
        )
        val node = ContainerNode(attributes = attrs)
        assertEquals("container-class", node.attributes.classes)
        assertEquals("my-container", node.attributes.name)
        assertEquals("custom-renderer", node.attributes.renderer)
    }

    @Test
    fun `ContainerNode default content is empty`() {
        val node = ContainerNode(attributes = ContainerAttributes())
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `ContainerNode default marks is empty`() {
        val node = ContainerNode(attributes = ContainerAttributes())
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `ContainerNode preserves content`() {
        val child = ParagraphNode()
        val node = ContainerNode(attributes = ContainerAttributes(), content = listOf(child))
        assertEquals(1, node.content.size)
    }

    @Test
    fun `ContainerNode preserves marks`() {
        val node = ContainerNode(attributes = ContainerAttributes(), marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }

    @Test
    fun `ContainerAttributes default fields are null`() {
        val attrs = ContainerAttributes()
        assertNull(attrs.classes)
        assertNull(attrs.name)
        assertNull(attrs.metadataId)
        assertNull(attrs.references)
        assertNull(attrs.renderer)
    }

    @Test
    fun `ContainerAttributes withClasses creates copy with new classes`() {
        val original = ContainerAttributes(name = "test")
        val modified = original.withClasses("new-class")
        assertEquals("new-class", modified.classes)
        assertEquals("test", modified.name)
        assertNull(original.classes)
    }

    @Test
    fun `ContainerAttributes withReferences clears when references is empty`() {
        val original = ContainerAttributes(references = listOf("ref1"))
        val modified = original.withReferences(null, emptyList())
        assertNull(modified.metadataId)
        assertNull(modified.references)
    }

    @Test
    fun `ContainerAttributes withReferences clears when references is null`() {
        val original = ContainerAttributes(references = listOf("ref1"))
        val modified = original.withReferences(null, null)
        assertNull(modified.metadataId)
        assertNull(modified.references)
    }

    @Test
    fun `ContainerAttributes withReferences sets values when references is non-empty`() {
        val original = ContainerAttributes()
        val refs = listOf("ref1", "ref2")
        val modified = original.withReferences(null, refs)
        assertNull(modified.metadataId)
        assertEquals(refs, modified.references)
    }
}
