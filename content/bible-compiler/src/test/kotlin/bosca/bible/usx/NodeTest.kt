package bosca.bible.usx

import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NodeTest {

    private fun createTextItem(): Text {
        val attrs = Attributes(emptyMap())
        return Text(attrs, Reference("GEN.1.1"), Position(0, 10))
    }

    @Test
    fun fieldPreservation() {
        val item = createTextItem()
        val node = Node(item = item, position = 5)
        assertEquals(item, node.item)
        assertEquals(5, node.position)
    }

    @Test
    fun equalityWhenFieldsMatch() {
        val item = createTextItem()
        val a = Node(item = item, position = 3)
        val b = Node(item = item, position = 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun inequalityWhenPositionDiffers() {
        val item = createTextItem()
        val a = Node(item = item, position = 1)
        val b = Node(item = item, position = 2)
        assertNotEquals(a, b)
    }

    @Test
    fun copyPreservesValues() {
        val item = createTextItem()
        val node = Node(item = item, position = 7)
        val copy = node.copy()
        assertEquals(node, copy)
    }

    @Test
    fun copyCanOverridePosition() {
        val item = createTextItem()
        val node = Node(item = item, position = 7)
        val modified = node.copy(position = 99)
        assertEquals(99, modified.position)
    }
}
