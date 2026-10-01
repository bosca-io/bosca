package bosca.bible.usx

import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootTest {

    @Test
    fun htmlClassIsEmpty() {
        val root = Root(Attributes(emptyMap()), null, Position(0))
        assertEquals("", root.htmlClass)
    }

    @Test
    fun itemsStartEmpty() {
        val root = Root(Attributes(emptyMap()), null, Position(0))
        assertTrue(root.items.isEmpty())
    }

    @Test
    fun addItemIncreasesSize() {
        val root = Root(Attributes(emptyMap()), null, Position(0, 100))
        val text = Text(Attributes(emptyMap()), Reference("GEN.1.1"), Position(0, 10))
        text.text = "test"
        root.add(text)
        assertEquals(1, root.items.size)
    }
}
