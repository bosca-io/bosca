package bosca.content.metadata.service

import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Branch/line coverage top-up for [ReferenceParser]. The sibling [ReferenceParserTest] already
 * exercises the common paths where every book carries both a long and short name and matches by
 * name. This file targets the arms that only fire when names are absent:
 *  - the `nameLong == null` branch (falls through to the short-name check),
 *  - the `nameShort == null` branch (falls through to the abbreviation check),
 *  - the abbreviation-only match arm,
 *  - the multi-hyphen range `else` arm (`rangeParts.size != 2`).
 */
class ReferenceParserCoverageTest {

    private val bibleService = mockk<BibleService>()

    private val metadataId = UUID.random()
    private val version = 1
    private val variant = "eng-NIV"

    private val bible = Bible(
        metadataId = metadataId,
        version = version,
        systemId = "niv",
        variant = variant,
        defaultVariant = true,
        name = "NIV",
        nameLocal = "NIV",
        description = "New International Version",
        abbreviation = "NIV",
        abbreviationLocal = "NIV",
        styles = JsonObject(emptyMap())
    )

    private fun createBook(
        usfm: String,
        nameShort: String?,
        nameLong: String?,
        abbreviation: String,
        sort: Int = 0
    ) = BibleBook(
        metadataId = metadataId,
        version = version,
        variant = variant,
        usfm = usfm,
        nameShort = nameShort,
        nameLong = nameLong,
        abbreviation = abbreviation,
        sort = sort
    )

    private fun createChapter(bookUsfm: String, usfm: String, sort: Int = 0) = BibleChapter(
        metadataId = metadataId,
        version = version,
        variant = variant,
        bookUsfm = bookUsfm,
        usfm = usfm,
        components = null,
        sort = sort
    )

    @AfterTest
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `parse matches book by abbreviation when both names are null`() = runTest {
        // nameLong == null AND nameShort == null forces the loop past the first two arms into the
        // abbreviation match arm (source lines 41 false, 44 false, 47 true).
        val revelation = createBook(usfm = "REV", nameShort = null, nameLong = null, abbreviation = "Rev")
        val chapters = (1..22).map { createChapter("REV", "REV.$it", it) }
        coEvery { bibleService.getBooks(bible) } returns listOf(revelation)
        coEvery { bibleService.getChapters(revelation) } returns chapters

        val result = ReferenceParser.parse(bibleService, bible, "Rev 1:1")

        assertEquals(1, result.size)
        assertEquals("REV.1.1", result[0].usfm)
    }

    @Test
    fun `parse matches book by short name when long name is null`() = runTest {
        // nameLong == null falls through to the nameShort arm (source line 41 false, 44 true).
        val job = createBook(usfm = "JOB", nameShort = "Job", nameLong = null, abbreviation = "Jb")
        val chapters = (1..42).map { createChapter("JOB", "JOB.$it", it) }
        coEvery { bibleService.getBooks(bible) } returns listOf(job)
        coEvery { bibleService.getChapters(job) } returns chapters

        val result = ReferenceParser.parse(bibleService, bible, "Job 1:1")

        assertEquals(1, result.size)
        assertEquals("JOB.1.1", result[0].usfm)
    }

    @Test
    fun `parse book-only reference resolves via abbreviation when names are null`() = runTest {
        // Combines the abbreviation match arm with the empty-nonBook early return (source line 55/56).
        val revelation = createBook(usfm = "REV", nameShort = null, nameLong = null, abbreviation = "Rev")
        coEvery { bibleService.getBooks(bible) } returns listOf(revelation)

        val result = ReferenceParser.parse(bibleService, bible, "Rev")

        assertEquals(1, result.size)
        assertEquals("REV", result[0].usfm)
    }

    @Test
    fun `parse collapses a multi-hyphen range to its first bound`() = runTest {
        // "1-2-3" splits into three parts, so rangeParts.size != 2 takes the else arm
        // (source lines 87-89): numberParts[1] becomes "1" and a single verse ref is returned.
        val genesis = createBook(usfm = "GEN", nameShort = "Gen", nameLong = "Genesis", abbreviation = "Gen")
        val chapters = (1..50).map { createChapter("GEN", "GEN.$it", it) }
        coEvery { bibleService.getBooks(bible) } returns listOf(genesis)
        coEvery { bibleService.getChapters(genesis) } returns chapters

        val result = ReferenceParser.parse(bibleService, bible, "Genesis 1:1-2-3")

        assertEquals(1, result.size)
        assertEquals("GEN.1.1", result[0].usfm)
    }

    @Test
    fun `parse joins two ranges from the same chapter into one reference`() = runTest {
        // Two comma-separated ranges in the same chapter exercise the joinedReferences append arm
        // (source line 24) where an existing usfm is extended with '+'.
        val genesis = createBook(usfm = "GEN", nameShort = "Gen", nameLong = "Genesis", abbreviation = "Gen")
        val chapters = (1..50).map { createChapter("GEN", "GEN.$it", it) }
        coEvery { bibleService.getBooks(bible) } returns listOf(genesis)
        coEvery { bibleService.getChapters(genesis) } returns chapters

        val result = ReferenceParser.parse(bibleService, bible, "Genesis 1:1-2, Genesis 1:8")

        assertEquals(1, result.size)
        assertEquals("GEN.1.1+GEN.1.2+GEN.1.8", result[0].usfm)
    }

    @Test
    fun `parse skips a null single reference among valid ones`() = runTest {
        // One recognized part and one unrecognized part exercises the reference != null guard
        // (source lines 14-16) on both sides while books have null long names.
        val revelation = createBook(usfm = "REV", nameShort = null, nameLong = null, abbreviation = "Rev")
        val chapters = (1..22).map { createChapter("REV", "REV.$it", it) }
        coEvery { bibleService.getBooks(bible) } returns listOf(revelation)
        coEvery { bibleService.getChapters(revelation) } returns chapters

        val result = ReferenceParser.parse(bibleService, bible, "Rev 1:1, Nonsense 5:5")

        assertEquals(1, result.size)
        assertEquals("REV.1.1", result[0].usfm)
        assertTrue(result.all { it.usfm.startsWith("REV") })
    }
}
