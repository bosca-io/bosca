package bosca.content.metadata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull

class BibleBookChapterTest {

    private val testId = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")

    private val sampleBook = BibleBook(
        metadataId = testId,
        version = 1,
        variant = "default",
        usfm = "GEN",
        nameShort = "Gen",
        nameLong = "Genesis",
        abbreviation = "Gen",
        sort = 1
    )

    private val sampleChapter = BibleChapter(
        metadataId = testId,
        version = 1,
        variant = "default",
        bookUsfm = "GEN",
        usfm = "GEN.1",
        components = JsonNull,
        sort = 1
    )

    @Test
    fun `field preservation after construction`() {
        val bookChapter = BibleBookChapter(book = sampleBook, chapter = sampleChapter)
        assertEquals(sampleBook, bookChapter.book)
        assertEquals(sampleChapter, bookChapter.chapter)
    }

    @Test
    fun `data class equality for identical instances`() {
        val bc1 = BibleBookChapter(book = sampleBook, chapter = sampleChapter)
        val bc2 = BibleBookChapter(book = sampleBook, chapter = sampleChapter)
        assertEquals(bc1, bc2)
        assertEquals(bc1.hashCode(), bc2.hashCode())
    }

    @Test
    fun `data class inequality when fields differ`() {
        val otherBook = sampleBook.copy(usfm = "EXO")
        val bc1 = BibleBookChapter(book = sampleBook, chapter = sampleChapter)
        val bc2 = BibleBookChapter(book = otherBook, chapter = sampleChapter)
        assertNotEquals(bc1, bc2)
    }

    @Test
    fun `copy preserves fields and allows overrides`() {
        val bookChapter = BibleBookChapter(book = sampleBook, chapter = sampleChapter)
        val otherChapter = sampleChapter.copy(usfm = "GEN.2", sort = 2)
        val copy = bookChapter.copy(chapter = otherChapter)
        assertEquals(sampleBook, copy.book)
        assertEquals(otherChapter, copy.chapter)
    }
}
