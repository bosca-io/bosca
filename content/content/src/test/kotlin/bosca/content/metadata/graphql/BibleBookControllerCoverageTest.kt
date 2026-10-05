package bosca.content.metadata.graphql

import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.service.BibleService
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BibleBookControllerCoverageTest {

    private val bibleService = mockk<BibleService>()
    private val controller = BibleBookController(bibleService)

    private val metadataId = UUID.random()

    private fun book(
        usfm: String = "GEN",
        nameShort: String? = "Gen",
        nameLong: String? = "Genesis",
        abbreviation: String = "Ge"
    ) = BibleBook(
        metadataId = metadataId,
        version = 1,
        variant = "variant-1",
        usfm = usfm,
        nameShort = nameShort,
        nameLong = nameLong,
        abbreviation = abbreviation,
        sort = 0
    )

    private fun chapter(usfm: String) = BibleChapter(
        metadataId = metadataId,
        version = 1,
        variant = "variant-1",
        bookUsfm = "GEN",
        usfm = usfm,
        components = null,
        sort = 0
    )

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    // ---- abbreviation ----

    @Test
    fun `abbreviation returns book abbreviation`() {
        assertEquals("Ge", controller.abbreviation(book(abbreviation = "Ge")))
    }

    // ---- nameShort ----

    @Test
    fun `nameShort returns book short name`() {
        assertEquals("Gen", controller.nameShort(book(nameShort = "Gen")))
    }

    @Test
    fun `nameShort returns null when book short name is null`() {
        assertNull(controller.nameShort(book(nameShort = null)))
    }

    // ---- nameLong ----

    @Test
    fun `nameLong returns book long name`() {
        assertEquals("Genesis", controller.nameLong(book(nameLong = "Genesis")))
    }

    @Test
    fun `nameLong returns null when book long name is null`() {
        assertNull(controller.nameLong(book(nameLong = null)))
    }

    // ---- reference (covers both elvis branches for human and humanShort) ----

    @Test
    fun `reference builds reference from usfm and non null names`() {
        val result = controller.reference(book(usfm = "GEN", nameLong = "Genesis", nameShort = "Gen"))

        assertEquals("GEN", result.usfm)
        assertEquals("Genesis", result.human)
        assertEquals("Gen", result.humanShort)
    }

    @Test
    fun `reference falls back to empty strings when names are null`() {
        val result = controller.reference(book(usfm = "EXO", nameLong = null, nameShort = null))

        assertEquals("EXO", result.usfm)
        assertEquals("", result.human)
        assertEquals("", result.humanShort)
    }

    // ---- chapters ----

    @Test
    fun `chapters maps each service chapter to a book chapter pairing`() = runTest {
        val genesis = book(usfm = "GEN")
        val ch1 = chapter("GEN.1")
        val ch2 = chapter("GEN.2")

        coEvery { bibleService.getChapters(genesis) } returns listOf(ch1, ch2)

        val result = controller.chapters(genesis)

        assertEquals(2, result.size)
        assertSame(genesis, result[0].book)
        assertSame(ch1, result[0].chapter)
        assertSame(genesis, result[1].book)
        assertSame(ch2, result[1].chapter)
    }

    @Test
    fun `chapters returns empty list when service has no chapters`() = runTest {
        val genesis = book(usfm = "GEN")

        coEvery { bibleService.getChapters(genesis) } returns emptyList()

        assertTrue(controller.chapters(genesis).isEmpty())
    }
}
