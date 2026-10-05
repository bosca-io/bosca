package bosca.bible

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BookTest {

    private fun createBook(): Book {
        val chapters = listOf(
            Chapter(reference = Reference("GEN.1"), component = null),
            Chapter(reference = Reference("GEN.2"), component = null),
            Chapter(reference = Reference("GEN.3"), component = null)
        )
        return Book(
            reference = Reference("GEN"),
            name = Name(id = "GEN", long = "Genesis", short = "Gen", abbreviation = "Ge"),
            chapters = chapters
        )
    }

    @Test
    fun getChapterByReference() {
        val book = createBook()
        val chapter = book[Reference("GEN.1")]
        assertNotNull(chapter)
        assertEquals(Reference("GEN.1"), chapter.reference)
    }

    @Test
    fun getChapterByVerseReference() {
        val book = createBook()
        val chapter = book[Reference("GEN.2")]
        assertNotNull(chapter)
        assertEquals(Reference("GEN.2"), chapter.reference)
    }

    @Test
    fun getChapterReturnsNullForNonExistent() {
        val book = createBook()
        val chapter = book[Reference("GEN.99")]
        assertNull(chapter)
    }

    @Test
    fun bookReferenceIsSet() {
        val book = createBook()
        assertEquals(Reference("GEN"), book.reference)
    }

    @Test
    fun bookNameIsSet() {
        val book = createBook()
        assertEquals("Genesis", book.name.long)
        assertEquals("Gen", book.name.short)
        assertEquals("Ge", book.name.abbreviation)
    }

    @Test
    fun chaptersListIsPreserved() {
        val book = createBook()
        assertEquals(3, book.chapters.size)
    }
}
