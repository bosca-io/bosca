package bosca.content.metadata.service

import bosca.bible.Reference
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferenceParserTest {

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

    private val genesis = createBook("GEN", "Gen", "Genesis", "Gen", sort = 0)
    private val exodus = createBook("EXO", "Exod", "Exodus", "Exod", sort = 1)
    private val psalm = createBook("PSA", "Ps", "Psalms", "Ps", sort = 18)
    private val firstJohn = createBook("1JN", "1 John", "1 John", "1Jn", sort = 61)
    private val judges = createBook("JDG", "Judges", "Judges", "Judg", sort = 6)
    private val jude = createBook("JUD", "Jude", "Jude", "Jud", sort = 64)

    private val genesisChapters = (1..50).map { createChapter("GEN", "GEN.$it", it) }
    private val exodusChapters = (1..40).map { createChapter("EXO", "EXO.$it", it) }
    private val psalmChapters = (1..150).map { createChapter("PSA", "PSA.$it", it) }
    private val firstJohnChapters = (1..5).map { createChapter("1JN", "1JN.$it", it) }
    private val judgesChapters = (1..21).map { createChapter("JDG", "JDG.$it", it) }
    private val judeChapters = listOf(createChapter("JUD", "JUD.1", 1))

    private val allBooks = listOf(genesis, exodus, judges, psalm, firstJohn, jude)

    private fun setupBooks() {
        coEvery { bibleService.getBooks(bible) } returns allBooks
        coEvery { bibleService.getChapters(genesis) } returns genesisChapters
        coEvery { bibleService.getChapters(exodus) } returns exodusChapters
        coEvery { bibleService.getChapters(psalm) } returns psalmChapters
        coEvery { bibleService.getChapters(firstJohn) } returns firstJohnChapters
        coEvery { bibleService.getChapters(judges) } returns judgesChapters
        coEvery { bibleService.getChapters(jude) } returns judeChapters
    }

    @Test
    fun `parse returns empty list for unrecognized input`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Unknown Book 1:1")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse recognizes book by long name`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis 1:1")
        assertEquals(1, result.size)
        assertEquals("GEN.1.1", result[0].usfm)
    }

    @Test
    fun `parse recognizes book by short name`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Gen 1:1")
        assertEquals(1, result.size)
        assertEquals("GEN.1.1", result[0].usfm)
    }

    @Test
    fun `parse recognizes book by abbreviation`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Gen 1:1")
        assertEquals(1, result.size)
        assertEquals("GEN.1.1", result[0].usfm)
    }

    @Test
    fun `parse handles case insensitivity`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "genesis 1:1")
        assertEquals(1, result.size)
        assertEquals("GEN.1.1", result[0].usfm)
    }

    @Test
    fun `parse handles book-only reference`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis")
        assertEquals(1, result.size)
        assertEquals("GEN", result[0].usfm)
    }

    @Test
    fun `parse handles chapter-only reference`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis 1")
        assertEquals(1, result.size)
        assertEquals("GEN.1", result[0].usfm)
    }

    @Test
    fun `parse handles verse range with hyphen`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis 1:1-3")
        assertEquals(1, result.size)
        assertEquals("GEN.1.1+GEN.1.2+GEN.1.3", result[0].usfm)
    }

    @Test
    fun `parse handles verse range with en-dash`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis 1:1\u20133")
        assertEquals(1, result.size)
        assertEquals("GEN.1.1+GEN.1.2+GEN.1.3", result[0].usfm)
    }

    @Test
    fun `parse handles comma-separated references`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis 1:1, Genesis 2:5")
        assertEquals(2, result.size)
        assertEquals("GEN.1.1", result[0].usfm)
        assertEquals("GEN.2.5", result[1].usfm)
    }

    @Test
    fun `parse joins references from the same chapter`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis 1:1, Genesis 1:5")
        assertEquals(1, result.size)
        assertEquals("GEN.1.1+GEN.1.5", result[0].usfm)
    }

    @Test
    fun `parse handles book with number prefix`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "1 John 3:16")
        assertEquals(1, result.size)
        assertEquals("1JN.3.16", result[0].usfm)
    }

    @Test
    fun `parse falls back to book reference for unknown chapter`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis 999")
        assertEquals(1, result.size)
        assertEquals("GEN", result[0].usfm)
    }

    @Test
    fun `parse handles single verse reference`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Psalms 23:4")
        assertEquals(1, result.size)
        assertEquals("PSA.23.4", result[0].usfm)
    }

    @Test
    fun `parse handles multiple comma-separated references in different books`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis 1:1, Exodus 3:14")
        assertEquals(2, result.size)
        val usfms = result.map { it.usfm }.toSet()
        assertTrue(usfms.contains("GEN.1.1"))
        assertTrue(usfms.contains("EXO.3.14"))
    }

    @Test
    fun `parse handles empty string`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse trims whitespace from parts`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "  Genesis 1:1 ,  Genesis 2:3  ")
        assertEquals(2, result.size)
    }

    @Test
    fun `parse handles verse range of single verse`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis 1:5-5")
        assertEquals(1, result.size)
        assertEquals("GEN.1.5", result[0].usfm)
    }

    @Test
    fun `parse keeps the same chapter of different books apart`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis 3:15, Exodus 3:14")
        assertEquals(listOf("GEN.3.15", "EXO.3.14"), result.map { it.usfm })
    }

    @Test
    fun `parse keeps different whole books apart`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Genesis, Exodus")
        assertEquals(listOf("GEN", "EXO"), result.map { it.usfm })
    }

    @Test
    fun `parse matches a whole name, not a shorter name that begins it`() = runTest {
        setupBooks()
        // Jude's abbreviation "Jud" begins "Judges", and Jude comes later in the canon.
        val result = ReferenceParser.parse(bibleService, bible, "Judges 5:1")
        assertEquals(listOf("JDG.5.1"), result.map { it.usfm })
    }

    @Test
    fun `parse needs a name to end where a word ends`() = runTest {
        setupBooks()
        // "Ps" begins "Psst" but isn't the word.
        assertTrue(ReferenceParser.parse(bibleService, bible, "Psst 23").isEmpty())
    }

    @Test
    fun `parse accepts the singular of a plural name`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Psalm 23")
        assertEquals(listOf("PSA.23"), result.map { it.usfm })
    }

    @Test
    fun `parse still accepts a name followed directly by its chapter`() = runTest {
        setupBooks()
        val result = ReferenceParser.parse(bibleService, bible, "Gen1:1")
        assertEquals(listOf("GEN.1.1"), result.map { it.usfm })
    }
}
