package bosca.bible.components

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerseEndTest {

    @Test
    fun `style is always null`() {
        val verseEnd = VerseEnd()
        assertNull(verseEnd.style)
    }

    @Test
    fun `implements IComponent`() {
        val verseEnd = VerseEnd()
        assertIs<IComponent>(verseEnd)
    }

    @Test
    fun `toString returns expected format`() {
        val verseEnd = VerseEnd()
        assertTrue(verseEnd.toString().contains("VerseEnd"))
    }
}
