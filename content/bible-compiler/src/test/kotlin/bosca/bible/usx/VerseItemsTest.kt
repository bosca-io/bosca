package bosca.bible.usx

import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals

class VerseItemsTest {

    private fun createVerseStart(number: String = "1", ref: Reference = Reference("GEN.1.1")): VerseStart {
        val attrs = Attributes(mapOf("STYLE" to "v", "NUMBER" to number, "SID" to "GEN 1:$number"))
        return VerseStart(attrs, ref, Position(0))
    }

    @Test
    fun fieldPreservation() {
        val ref = Reference("GEN.1.5")
        val vs = createVerseStart("5", ref)
        val vi = VerseItems(ref, Position(0, 100), vs)
        assertEquals(ref, vi.reference)
        assertEquals("5", vi.verse)
    }

    @Test
    fun initialItemsContainsVerseStart() {
        val ref = Reference("GEN.1.1")
        val vs = createVerseStart("1", ref)
        val vi = VerseItems(ref, Position(0, 50), vs)
        assertEquals(1, vi.items.size)
        assertEquals(vs, vi.items[0])
    }

    @Test
    fun addItemIncreasesSize() {
        val ref = Reference("GEN.1.1")
        val vs = createVerseStart("1", ref)
        val vi = VerseItems(ref, Position(0, 50), vs)
        val text = Text(Attributes(emptyMap()), ref, Position(10, 30))
        text.text = "In the beginning"
        vi.add(text)
        assertEquals(2, vi.items.size)
    }

    @Test
    fun htmlClassIsVerses() {
        val ref = Reference("GEN.1.1")
        val vs = createVerseStart("1", ref)
        val vi = VerseItems(ref, Position(0, 50), vs)
        assertEquals("verses", vi.htmlClass)
    }

    @Test
    fun htmlAttributesAreEmpty() {
        val ref = Reference("GEN.1.1")
        val vs = createVerseStart("1", ref)
        val vi = VerseItems(ref, Position(0, 50), vs)
        assertEquals(true, vi.htmlAttributes.isEmpty())
    }

    @Test
    fun toStringConcatenatesItemsAndTrims() {
        val ref = Reference("GEN.1.1")
        val vs = createVerseStart("1", ref)
        val vi = VerseItems(ref, Position(0, 50), vs)
        val text = Text(Attributes(emptyMap()), ref, Position(10, 30))
        text.text = "Hello world "
        vi.add(text)
        val ctx = StringContext(includeVerseNumbers = true)
        val result = vi.toString(ctx)
        assertEquals(true, result.contains("1."))
        assertEquals(true, result.contains("Hello world"))
    }

    @Test
    fun toStringWithoutVerseNumbers() {
        val ref = Reference("GEN.1.1")
        val vs = createVerseStart("1", ref)
        val vi = VerseItems(ref, Position(0, 50), vs)
        val text = Text(Attributes(emptyMap()), ref, Position(10, 30))
        text.text = "In the beginning"
        vi.add(text)
        val result = vi.toString(StringContext())
        assertEquals("In the beginning", result)
    }
}
