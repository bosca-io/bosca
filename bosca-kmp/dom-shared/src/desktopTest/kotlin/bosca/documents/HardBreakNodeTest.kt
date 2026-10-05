package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HardBreakNodeTest {

    @Test
    fun `HardBreakNode default creation has empty content`() {
        val node = HardBreakNode()
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `HardBreakNode default creation has empty marks`() {
        val node = HardBreakNode()
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `HardBreakNode default attributes is EmptyDocumentAttributes`() {
        val node = HardBreakNode()
        assertTrue(node.attributes is EmptyDocumentAttributes)
    }

    @Test
    fun `HardBreakNode preserves marks`() {
        val node = HardBreakNode(marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }
}
