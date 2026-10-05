package bosca.documents

import bosca.documents.marks.Bold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HorizontalRuleNodeTest {

    @Test
    fun `HorizontalRuleNode default creation has empty content`() {
        val node = HorizontalRuleNode()
        assertTrue(node.content.isEmpty())
    }

    @Test
    fun `HorizontalRuleNode default creation has empty marks`() {
        val node = HorizontalRuleNode()
        assertTrue(node.marks.isEmpty())
    }

    @Test
    fun `HorizontalRuleNode default attributes is EmptyDocumentAttributes`() {
        val node = HorizontalRuleNode()
        assertTrue(node.attributes is EmptyDocumentAttributes)
    }

    @Test
    fun `HorizontalRuleNode preserves marks`() {
        val node = HorizontalRuleNode(marks = listOf(Bold()))
        assertEquals(1, node.marks.size)
    }
}
