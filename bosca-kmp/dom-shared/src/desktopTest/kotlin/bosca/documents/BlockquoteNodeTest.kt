package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockquoteNodeTest {

    @Test
    fun `BlockquoteNode default creation has empty content`() {
        val node = BlockquoteNode()
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `BlockquoteNode default creation has empty marks`() {
        val node = BlockquoteNode()
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `BlockquoteNode default attributes is EmptyDocumentAttributes`() {
        val node = BlockquoteNode()
        assertTrue(node.attributes is EmptyDocumentAttributes)
    }

    @Test
    fun `BlockquoteNode preserves content`() {
        val child = ParagraphNode()
        val node = BlockquoteNode(content = listOf(child))
        assertEquals(1, node.content.size)
    }

    @Test
    fun `BlockquoteNode preserves marks`() {
        val node = BlockquoteNode(marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }
}
