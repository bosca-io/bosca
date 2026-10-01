package bosca.bible.usx

import kotlin.test.Test
import kotlin.test.assertEquals

class UsxReferenceTest {

    @Test
    fun locFieldPreservation() {
        val attrs = Attributes(mapOf("LOC" to "GEN 1:1"))
        val ref = Reference(attrs, null, Position(0))
        assertEquals("GEN 1:1", ref.loc)
    }

    @Test
    fun locNullStringWhenMissing() {
        val attrs = Attributes(emptyMap())
        val ref = Reference(attrs, null, Position(0))
        assertEquals("null", ref.loc)
    }

    @Test
    fun htmlClassIsEmpty() {
        val attrs = Attributes(mapOf("LOC" to "GEN 1:1"))
        val ref = Reference(attrs, null, Position(0))
        assertEquals("", ref.htmlClass)
    }

    @Test
    fun canAddTextItems() {
        val attrs = Attributes(mapOf("LOC" to "GEN 1:1"))
        val ref = Reference(attrs, null, Position(0, 100))
        val text = Text(Attributes(emptyMap()), null, Position(5, 20))
        text.text = "reference text"
        ref.add(text)
        assertEquals(1, ref.items.size)
    }
}
