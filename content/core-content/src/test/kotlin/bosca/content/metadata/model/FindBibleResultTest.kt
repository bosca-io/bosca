package bosca.content.metadata.model

import bosca.bible.Reference
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class FindBibleResultTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    private fun createBook() = BibleBook(
        metadataId = testId,
        version = 1,
        variant = "default",
        usfm = "GEN",
        nameShort = "Gen",
        nameLong = "Genesis",
        abbreviation = "GEN",
        sort = 1
    )

    private fun createChapter() = BibleChapter(
        metadataId = testId,
        version = 1,
        variant = "default",
        bookUsfm = "GEN",
        usfm = "GEN.1",
        components = null,
        sort = 1
    )

    @Test
    fun `stores all fields`() {
        val book = createBook()
        val chapter = createChapter()
        val component = JsonPrimitive("test-component")
        val reference = Reference(usfm = "GEN.1.1")

        val result = FindBibleResult(
            book = book,
            chapter = chapter,
            component = component,
            human = "Genesis 1:1",
            humanShort = "Gen 1:1",
            reference = reference
        )

        assertEquals(book, result.book)
        assertEquals(chapter, result.chapter)
        assertEquals(component, result.component)
        assertEquals("Genesis 1:1", result.human)
        assertEquals("Gen 1:1", result.humanShort)
        assertEquals(reference, result.reference)
    }

    @Test
    fun `nullable chapter and component`() {
        val book = createBook()
        val reference = Reference(usfm = "GEN")

        val result = FindBibleResult(
            book = book,
            chapter = null,
            component = null,
            human = "Genesis",
            humanShort = "Gen",
            reference = reference
        )

        assertNull(result.chapter)
        assertNull(result.component)
    }

    @Test
    fun `data class equality`() {
        val book = createBook()
        val reference = Reference(usfm = "GEN.1.1")
        val a = FindBibleResult(book = book, chapter = null, component = null, human = "Genesis 1:1", humanShort = "Gen 1:1", reference = reference)
        val b = FindBibleResult(book = book, chapter = null, component = null, human = "Genesis 1:1", humanShort = "Gen 1:1", reference = reference)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality on different human string`() {
        val book = createBook()
        val reference = Reference(usfm = "GEN.1.1")
        val a = FindBibleResult(book = book, chapter = null, component = null, human = "Genesis 1:1", humanShort = "Gen 1:1", reference = reference)
        val b = FindBibleResult(book = book, chapter = null, component = null, human = "Genesis 1:2", humanShort = "Gen 1:2", reference = reference)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies fields`() {
        val book = createBook()
        val reference = Reference(usfm = "GEN.1.1")
        val original = FindBibleResult(book = book, chapter = null, component = null, human = "Genesis 1:1", humanShort = "Gen 1:1", reference = reference)
        val copied = original.copy(human = "Genesis 1:2", humanShort = "Gen 1:2")
        assertEquals("Genesis 1:2", copied.human)
        assertEquals("Gen 1:2", copied.humanShort)
        assertEquals(book, copied.book)
    }
}
