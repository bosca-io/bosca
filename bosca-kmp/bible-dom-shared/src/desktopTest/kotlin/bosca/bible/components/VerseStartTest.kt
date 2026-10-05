package bosca.bible.components

import bosca.bible.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerseStartTest {

    @Test
    fun `creation preserves reference`() {
        val ref = Reference("GEN.1.1")
        val verseStart = VerseStart(ref)
        assertEquals(ref, verseStart.reference)
    }

    @Test
    fun `default style is null`() {
        val verseStart = VerseStart(Reference("GEN.1.1"))
        assertNull(verseStart.style)
    }

    @Test
    fun `custom style is preserved`() {
        val style = bosca.bible.style.Style(id = "verse-style")
        val verseStart = VerseStart(Reference("GEN.1.1"), style = style)
        assertEquals("verse-style", verseStart.style!!.id)
    }

    @Test
    fun `implements IComponent`() {
        val verseStart = VerseStart(Reference("GEN.1.1"))
        assertIs<IComponent>(verseStart)
    }

    @Test
    fun `toString contains reference info`() {
        val verseStart = VerseStart(Reference("MAT.5.3"))
        val str = verseStart.toString()
        assertTrue(str.contains("VerseStart"))
        assertTrue(str.contains("MAT.5.3"))
    }

    @Test
    fun `reference with composite USFM`() {
        val verseStart = VerseStart(Reference("MAT.5.3+MAT.5.4"))
        assertEquals("MAT.5.3+MAT.5.4", verseStart.reference.usfm)
    }
}
