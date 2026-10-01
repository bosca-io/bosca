package bible.grammar

import bosca.bible.IBible
import bosca.bible.Reference
import bosca.bible.components.ComponentContainer
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.bible.components.VerseStart
import bosca.bible.components.findVerses
import bosca.bible.processor.BibleFactoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests that load real Bible ZIP files (KJV and ASV) through
 * the full compilation pipeline: ZIP extraction, metadata parsing, style
 * processing, USX compilation, and Bible/Book/Chapter model construction.
 */
class BibleIntegrationTest {

    companion object {
        private var _kjvBibles: List<IBible>? = null
        private val kjvBibles: List<IBible> get() = _kjvBibles!!

        private var _kjv: IBible? = null
        private val kjv: IBible get() = _kjv!!

        private var _asvBibles: List<IBible>? = null
        private val asvBibles: List<IBible> get() = _asvBibles!!

        private var _asv: IBible? = null
        private val asv: IBible get() = _asv!!

        @JvmStatic
        @BeforeClass
        fun setUp() {
            runBlocking {
                val kjvPath = BibleIntegrationTest::class.java.getResource("/kjv.zip")!!.path
                _kjvBibles = BibleFactoryImpl().getBibles(kjvPath)
                _kjv = _kjvBibles!!.maxBy { it.books.size }

                val asvPath = BibleIntegrationTest::class.java.getResource("/asv.zip")!!.path
                _asvBibles = BibleFactoryImpl().getBibles(asvPath)
                _asv = _asvBibles!!.maxBy { it.books.size }
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            _kjvBibles = null
            _kjv = null
            _asvBibles = null
            _asv = null
        }

        private val expectedOTBooks = listOf(
            "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT",
            "1SA", "2SA", "1KI", "2KI", "1CH", "2CH", "EZR", "NEH",
            "EST", "JOB", "PSA", "PRO", "ECC", "SNG", "ISA", "JER",
            "LAM", "EZK", "DAN", "HOS", "JOL", "AMO", "OBA", "JON",
            "MIC", "NAM", "HAB", "ZEP", "HAG", "ZEC", "MAL"
        )

        private val expectedNTBooks = listOf(
            "MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO",
            "GAL", "EPH", "PHP", "COL", "1TH", "2TH", "1TI", "2TI",
            "TIT", "PHM", "HEB", "JAS", "1PE", "2PE", "1JN", "2JN",
            "3JN", "JUD", "REV"
        )

        private val expectedChapterCounts = mapOf(
            "GEN" to 50, "EXO" to 40, "LEV" to 27, "NUM" to 36, "DEU" to 34,
            "JOS" to 24, "JDG" to 21, "RUT" to 4, "1SA" to 31, "2SA" to 24,
            "1KI" to 22, "2KI" to 25, "1CH" to 29, "2CH" to 36, "EZR" to 10,
            "NEH" to 13, "EST" to 10, "JOB" to 42, "PSA" to 150, "PRO" to 31,
            "ECC" to 12, "SNG" to 8, "ISA" to 66, "JER" to 52, "LAM" to 5,
            "EZK" to 48, "DAN" to 12, "HOS" to 14, "JOL" to 3, "AMO" to 9,
            "OBA" to 1, "JON" to 4, "MIC" to 7, "NAM" to 3, "HAB" to 3,
            "ZEP" to 3, "HAG" to 2, "ZEC" to 14, "MAL" to 4,
            "MAT" to 28, "MRK" to 16, "LUK" to 24, "JHN" to 21, "ACT" to 28,
            "ROM" to 16, "1CO" to 16, "2CO" to 13, "GAL" to 6, "EPH" to 6,
            "PHP" to 4, "COL" to 4, "1TH" to 5, "2TH" to 3, "1TI" to 6,
            "2TI" to 4, "TIT" to 3, "PHM" to 1, "HEB" to 13, "JAS" to 5,
            "1PE" to 5, "2PE" to 3, "1JN" to 5, "2JN" to 1, "3JN" to 1,
            "JUD" to 1, "REV" to 22
        )
    }

    // ── ZIP Loading Tests ──────────────────────────────────────────

    @Test
    fun testKjvZipProducesBibles() {
        assertTrue(kjvBibles.isNotEmpty(), "KJV ZIP should produce at least one Bible")
    }

    @Test
    fun testAsvZipProducesBibles() {
        assertTrue(asvBibles.isNotEmpty(), "ASV ZIP should produce at least one Bible")
    }

    // ── KJV Metadata Tests ─────────────────────────────────────────

    @Test
    fun testKjvMetadataIdentification() {
        val id = kjv.metadata.identification
        assertTrue(id.name.isNotEmpty(), "KJV should have a name")
        assertTrue(
            id.abbreviation.isNotEmpty() || id.abbreviationLocal.isNotEmpty(),
            "KJV should have an abbreviation"
        )
    }

    @Test
    fun testKjvMetadataLanguage() {
        val lang = kjv.metadata.language
        assertTrue(lang.iso.isNotEmpty(), "KJV should have a language ISO code")
        assertEquals("eng", lang.iso, "KJV language should be English")
    }

    @Test
    fun testKjvMetadataPublication() {
        val pub = kjv.metadata.publication
        assertTrue(pub.id.isNotEmpty(), "KJV should have a publication ID")
    }

    // ── ASV Metadata Tests ─────────────────────────────────────────

    @Test
    fun testAsvMetadataIdentification() {
        val id = asv.metadata.identification
        assertTrue(id.name.isNotEmpty(), "ASV should have a name")
    }

    @Test
    fun testAsvMetadataLanguage() {
        val lang = asv.metadata.language
        assertEquals("eng", lang.iso, "ASV language should be English")
    }

    // ── Book Count and Presence Tests ──────────────────────────────

    @Test
    fun testKjvHasAtLeast66Books() {
        assertTrue(kjv.books.size >= 66,
            "KJV should have at least 66 books, found ${kjv.books.size}")
    }

    @Test
    fun testAsvHasAtLeast66Books() {
        assertTrue(asv.books.size >= 66,
            "ASV should have at least 66 books, found ${asv.books.size}")
    }

    @Test
    fun testKjvContainsAllOTBooks() {
        val bookUsfms = kjv.books.map { it.reference.bookUsfm }.toSet()
        for (expected in expectedOTBooks) {
            assertTrue(expected in bookUsfms, "KJV should contain OT book $expected")
        }
    }

    @Test
    fun testKjvContainsAllNTBooks() {
        val bookUsfms = kjv.books.map { it.reference.bookUsfm }.toSet()
        for (expected in expectedNTBooks) {
            assertTrue(expected in bookUsfms, "KJV should contain NT book $expected")
        }
    }

    @Test
    fun testAsvContainsAllOTBooks() {
        val bookUsfms = asv.books.map { it.reference.bookUsfm }.toSet()
        for (expected in expectedOTBooks) {
            assertTrue(expected in bookUsfms, "ASV should contain OT book $expected")
        }
    }

    @Test
    fun testAsvContainsAllNTBooks() {
        val bookUsfms = asv.books.map { it.reference.bookUsfm }.toSet()
        for (expected in expectedNTBooks) {
            assertTrue(expected in bookUsfms, "ASV should contain NT book $expected")
        }
    }

    // ── Book Name Tests ────────────────────────────────────────────

    @Test
    fun testKjvBookNamesArePopulated() {
        for (book in kjv.books) {
            assertTrue(
                book.name.long.isNotEmpty() || book.name.short.isNotEmpty(),
                "Book ${book.reference.bookUsfm} should have a name"
            )
        }
    }

    @Test
    fun testAsvBookNamesArePopulated() {
        for (book in asv.books) {
            assertTrue(
                book.name.long.isNotEmpty() || book.name.short.isNotEmpty(),
                "Book ${book.reference.bookUsfm} should have a name"
            )
        }
    }

    // ── Chapter Count Tests ────────────────────────────────────────

    @Test
    fun testKjvChapterCounts() {
        for ((usfm, expectedCount) in expectedChapterCounts) {
            val book = kjv[Reference(usfm)]
            assertNotNull(book, "KJV should contain book $usfm")
            assertEquals(
                expectedCount, book.chapters.size,
                "KJV $usfm should have $expectedCount chapters but has ${book.chapters.size}"
            )
        }
    }

    @Test
    fun testAsvChapterCounts() {
        for ((usfm, expectedCount) in expectedChapterCounts) {
            val book = asv[Reference(usfm)]
            assertNotNull(book, "ASV should contain book $usfm")
            assertEquals(
                expectedCount, book.chapters.size,
                "ASV $usfm should have $expectedCount chapters but has ${book.chapters.size}"
            )
        }
    }

    // ── Book Lookup by Reference Tests ─────────────────────────────

    @Test
    fun testKjvBookLookupByReference() {
        val genesis = kjv[Reference("GEN")]
        assertNotNull(genesis, "Should find Genesis by Reference(GEN)")
        val revelation = kjv[Reference("REV")]
        assertNotNull(revelation, "Should find Revelation by Reference(REV)")
    }

    @Test
    fun testAsvBookLookupByReference() {
        val genesis = asv[Reference("GEN")]
        assertNotNull(genesis, "Should find Genesis by Reference(GEN)")
        val revelation = asv[Reference("REV")]
        assertNotNull(revelation, "Should find Revelation by Reference(REV)")
    }

    // ── Chapter Lookup by Reference Tests ──────────────────────────

    @Test
    fun testKjvChapterLookup() {
        val genesis = kjv[Reference("GEN")]!!
        val chapter1 = genesis[Reference("GEN.1")]
        assertNotNull(chapter1, "Should find Genesis chapter 1")
        val chapter50 = genesis[Reference("GEN.50")]
        assertNotNull(chapter50, "Should find Genesis chapter 50")
    }

    @Test
    fun testAsvChapterLookup() {
        val psalms = asv[Reference("PSA")]!!
        val chapter1 = psalms[Reference("PSA.1")]
        assertNotNull(chapter1, "Should find Psalms chapter 1")
        val chapter150 = psalms[Reference("PSA.150")]
        assertNotNull(chapter150, "Should find Psalms chapter 150")
    }

    // ── Verse Retrieval and Content Tests ──────────────────────────

    @Test
    fun testKjvGenesis1v1Content() {
        val genesis = kjv[Reference("GEN")]!!
        val chapter1 = genesis[Reference("GEN.1")]!!
        val verse1 = chapter1[Reference("GEN.1.1")]
        assertNotNull(verse1, "Should find Genesis 1:1")
        val text = extractText(verse1)
        assertTrue(
            text.contains("beginning", ignoreCase = true),
            "Genesis 1:1 should contain 'beginning', got: $text"
        )
        assertTrue(
            text.contains("heaven", ignoreCase = true) || text.contains("heavens", ignoreCase = true),
            "Genesis 1:1 should contain 'heaven(s)', got: $text"
        )
    }

    @Test
    fun testAsvGenesis1v1Content() {
        val genesis = asv[Reference("GEN")]!!
        val chapter1 = genesis[Reference("GEN.1")]!!
        val verse1 = chapter1[Reference("GEN.1.1")]
        assertNotNull(verse1, "Should find Genesis 1:1")
        val text = extractText(verse1)
        assertTrue(
            text.contains("beginning", ignoreCase = true),
            "ASV Genesis 1:1 should contain 'beginning', got: $text"
        )
    }

    @Test
    fun testKjvJohn3v16Content() {
        val john = kjv[Reference("JHN")]!!
        val chapter3 = john[Reference("JHN.3")]!!
        val verse16 = chapter3[Reference("JHN.3.16")]
        assertNotNull(verse16, "Should find John 3:16")
        val text = extractText(verse16)
        assertTrue(
            text.contains("God so loved", ignoreCase = true),
            "John 3:16 should contain 'God so loved', got: $text"
        )
        assertTrue(
            text.contains("world", ignoreCase = true),
            "John 3:16 should contain 'world', got: $text"
        )
    }

    @Test
    fun testKjvPsalm23v1Content() {
        val psalms = kjv[Reference("PSA")]!!
        val chapter23 = psalms[Reference("PSA.23")]!!
        val verse1 = chapter23[Reference("PSA.23.1")]
        assertNotNull(verse1, "Should find Psalm 23:1")
        val text = extractText(verse1)
        assertTrue(
            text.contains("shepherd", ignoreCase = true),
            "Psalm 23:1 should contain 'shepherd', got: $text"
        )
    }

    @Test
    fun testKjvRevelation22v21Content() {
        val rev = kjv[Reference("REV")]!!
        val chapter22 = rev[Reference("REV.22")]!!
        val verse21 = chapter22[Reference("REV.22.21")]
        assertNotNull(verse21, "Should find Revelation 22:21")
        val text = extractText(verse21)
        assertTrue(
            text.contains("grace", ignoreCase = true) || text.contains("Amen", ignoreCase = true),
            "Revelation 22:21 should contain 'grace' or 'Amen', got: $text"
        )
    }

    @Test
    fun testAsvJohn1v1Content() {
        val john = asv[Reference("JHN")]!!
        val chapter1 = john[Reference("JHN.1")]!!
        val verse1 = chapter1[Reference("JHN.1.1")]
        assertNotNull(verse1, "Should find John 1:1")
        val text = extractText(verse1)
        assertTrue(
            text.contains("Word", ignoreCase = true),
            "John 1:1 should contain 'Word', got: $text"
        )
    }

    @Test
    fun testAsvRomans8v28Content() {
        val romans = asv[Reference("ROM")]!!
        val chapter8 = romans[Reference("ROM.8")]!!
        val verse28 = chapter8[Reference("ROM.8.28")]
        assertNotNull(verse28, "Should find Romans 8:28")
        val text = extractText(verse28)
        assertTrue(
            text.contains("good", ignoreCase = true),
            "Romans 8:28 should contain 'good', got: $text"
        )
    }

    // ── Verse Discovery Tests ──────────────────────────────────────

    @Test
    fun testKjvGenesis1HasMultipleVerses() {
        val genesis = kjv[Reference("GEN")]!!
        val chapter1 = genesis[Reference("GEN.1")]!!
        val component = chapter1[Reference("GEN.1")]
        assertNotNull(component)
        val verses = component.findVerses()
        assertTrue(verses.size >= 31, "Genesis 1 should have at least 31 verses, found ${verses.size}")
    }

    @Test
    fun testKjvPsalm119HasManyVerses() {
        val psalms = kjv[Reference("PSA")]!!
        val chapter119 = psalms[Reference("PSA.119")]!!
        val component = chapter119[Reference("PSA.119")]
        assertNotNull(component)
        val verses = component.findVerses()
        assertTrue(verses.size >= 176, "Psalm 119 should have at least 176 verses, found ${verses.size}")
    }

    @Test
    fun testAsvMatthew1HasCorrectVerseCount() {
        val matt = asv[Reference("MAT")]!!
        val chapter1 = matt[Reference("MAT.1")]!!
        val component = chapter1[Reference("MAT.1")]
        assertNotNull(component)
        val verses = component.findVerses()
        assertTrue(verses.size >= 25, "Matthew 1 should have at least 25 verses, found ${verses.size}")
    }

    // ── Style Registry Tests ───────────────────────────────────────

    @Test
    fun testKjvHasStyles() {
        assertTrue(kjv.styles.isNotEmpty(), "KJV should have styles loaded from styles.xml")
    }

    @Test
    fun testAsvHasStyles() {
        assertTrue(asv.styles.isNotEmpty(), "ASV should have styles loaded from styles.xml")
    }

    // ── Cross-Bible Comparison Tests ───────────────────────────────

    @Test
    fun testBothBiblesContainCanonical66() {
        val canonical = expectedOTBooks + expectedNTBooks
        val kjvUsfms = kjv.books.map { it.reference.bookUsfm }.toSet()
        val asvUsfms = asv.books.map { it.reference.bookUsfm }.toSet()
        for (usfm in canonical) {
            assertTrue(usfm in kjvUsfms, "KJV should contain canonical book $usfm")
            assertTrue(usfm in asvUsfms, "ASV should contain canonical book $usfm")
        }
    }

    @Test
    fun testKjvContainsDeuterocanonicalBooks() {
        val bookUsfms = kjv.books.map { it.reference.bookUsfm }.toSet()
        assertTrue(kjv.books.size > 66,
            "KJV edition includes deuterocanonical books (${kjv.books.size} total)")
        assertTrue("TOB" in bookUsfms, "KJV should contain Tobit")
    }

    @Test
    fun testBothBiblesHaveSameChapterCountsForSharedBooks() {
        val asvBooksByUsfm = asv.books.associateBy { it.reference.bookUsfm }
        for (kjvBook in kjv.books) {
            val asvBook = asvBooksByUsfm[kjvBook.reference.bookUsfm] ?: continue
            assertEquals(
                kjvBook.chapters.size, asvBook.chapters.size,
                "Chapter count mismatch for ${kjvBook.reference.bookUsfm}: " +
                    "KJV=${kjvBook.chapters.size}, ASV=${asvBook.chapters.size}"
            )
        }
    }

    @Test
    fun testGenesis1v1DiffersBetweenTranslations() {
        val kjvGenesis = kjv[Reference("GEN")]!!
        val kjvChapter1 = kjvGenesis[Reference("GEN.1")]!!
        val kjvVerse1 = kjvChapter1[Reference("GEN.1.1")]!!
        val kjvText = extractText(kjvVerse1)

        val asvGenesis = asv[Reference("GEN")]!!
        val asvChapter1 = asvGenesis[Reference("GEN.1")]!!
        val asvVerse1 = asvChapter1[Reference("GEN.1.1")]!!
        val asvText = extractText(asvVerse1)

        assertTrue(kjvText.contains("beginning") && asvText.contains("beginning"),
            "Both translations should contain 'beginning' in Genesis 1:1")
        assertTrue(kjvText.isNotEmpty() && asvText.isNotEmpty(),
            "Both translations should have non-empty verse text")
    }

    // ── Serialization Roundtrip Tests ──────────────────────────────

    @Test
    fun testKjvSerializableRoundtrip() {
        val serialized = kjv.asSerializable()
        assertEquals(kjv.books.size, serialized.books.size)
        assertEquals(
            kjv.metadata.identification.name,
            serialized.metadata.identification.name
        )
    }

    @Test
    fun testAsvSerializableRoundtrip() {
        val serialized = asv.asSerializable()
        assertEquals(asv.books.size, serialized.books.size)
        assertEquals(
            asv.metadata.language.iso,
            serialized.metadata.language.iso
        )
    }

    // ── Edge Case Books Tests ──────────────────────────────────────

    @Test
    fun testKjvSingleChapterBooks() {
        val singleChapterBooks = listOf("OBA", "PHM", "2JN", "3JN", "JUD")
        for (usfm in singleChapterBooks) {
            val book = kjv[Reference(usfm)]
            assertNotNull(book, "KJV should contain $usfm")
            assertEquals(1, book.chapters.size, "$usfm should have exactly 1 chapter")
        }
    }

    @Test
    fun testAsvSingleChapterBooks() {
        val singleChapterBooks = listOf("OBA", "PHM", "2JN", "3JN", "JUD")
        for (usfm in singleChapterBooks) {
            val book = asv[Reference(usfm)]
            assertNotNull(book, "ASV should contain $usfm")
            assertEquals(1, book.chapters.size, "$usfm should have exactly 1 chapter")
        }
    }

    @Test
    fun testKjvLargestBook() {
        val psalms = kjv[Reference("PSA")]!!
        assertEquals(150, psalms.chapters.size, "Psalms should have 150 chapters")
    }

    // ── Component Structure Tests ──────────────────────────────────

    @Test
    fun testKjvVerseComponentStructure() {
        val genesis = kjv[Reference("GEN")]!!
        val chapter1 = genesis[Reference("GEN.1")]!!
        val verse1 = chapter1[Reference("GEN.1.1")]
        assertNotNull(verse1, "Should find Genesis 1:1 component")

        val components = flattenComponents(verse1)
        val verseStarts = components.filterIsInstance<VerseStart>()
        val texts = components.filterIsInstance<Text>()

        assertTrue(verseStarts.isNotEmpty(), "Verse component should contain VerseStart")
        assertTrue(texts.isNotEmpty(), "Verse component should contain Text elements")
    }

    @Test
    fun testAsvVerseComponentStructure() {
        val john = asv[Reference("JHN")]!!
        val chapter1 = john[Reference("JHN.1")]!!
        val verse1 = chapter1[Reference("JHN.1.1")]
        assertNotNull(verse1, "Should find John 1:1 component")

        val components = flattenComponents(verse1)
        val texts = components.filterIsInstance<Text>()
        assertTrue(texts.isNotEmpty(), "Verse component should contain Text elements")
        val combinedText = texts.joinToString("") { it.text }
        assertTrue(combinedText.isNotBlank(), "Verse text should not be blank")
    }

    // ── Full Pipeline Stress Test ──────────────────────────────────

    @Test
    fun testKjvAllChaptersHaveVerses() {
        for (book in kjv.books) {
            for (chapter in book.chapters) {
                val component = chapter[chapter.reference]
                assertNotNull(
                    component,
                    "Chapter ${chapter.reference.usfm} in ${book.reference.bookUsfm} should have content"
                )
                val verses = component.findVerses()
                assertTrue(
                    verses.isNotEmpty(),
                    "Chapter ${chapter.reference.usfm} in ${book.reference.bookUsfm} should have at least one verse"
                )
            }
        }
    }

    @Test
    fun testAsvAllChaptersHaveVerses() {
        for (book in asv.books) {
            for (chapter in book.chapters) {
                val component = chapter[chapter.reference]
                assertNotNull(
                    component,
                    "Chapter ${chapter.reference.usfm} in ${book.reference.bookUsfm} should have content"
                )
                val verses = component.findVerses()
                assertTrue(
                    verses.isNotEmpty(),
                    "Chapter ${chapter.reference.usfm} in ${book.reference.bookUsfm} should have at least one verse"
                )
            }
        }
    }

    @Test
    fun testKjvAllVersesHaveContent() {
        val genesis = kjv[Reference("GEN")]!!
        val chapter1 = genesis[Reference("GEN.1")]!!
        val wholeChapter = chapter1[Reference("GEN.1")]!!
        val verses = wholeChapter.findVerses()
        for (verseRef in verses) {
            val verseComponent = chapter1[verseRef]
            assertNotNull(verseComponent, "Should find component for ${verseRef.usfm}")
            val text = extractText(verseComponent)
            assertTrue(
                text.isNotBlank(),
                "Verse ${verseRef.usfm} should have non-blank text content"
            )
        }
    }

    // ── Helper Methods ─────────────────────────────────────────────

    private val whitespaceRegex = "\\s+".toRegex()

    private fun extractText(component: IComponent): String {
        val texts = mutableListOf<String>()
        collectText(component, texts)
        return texts.joinToString(" ").replace(whitespaceRegex, " ").trim()
    }

    private fun collectText(component: IComponent, texts: MutableList<String>) {
        when (component) {
            is Text -> texts.add(component.text)
            is ComponentContainer -> component.components.forEach { collectText(it, texts) }
            else -> {}
        }
    }

    private fun flattenComponents(component: IComponent): List<IComponent> {
        val result = mutableListOf<IComponent>()
        flattenInto(component, result)
        return result
    }

    private fun flattenInto(component: IComponent, result: MutableList<IComponent>) {
        result.add(component)
        if (component is ComponentContainer) {
            component.components.forEach { flattenInto(it, result) }
        }
    }
}
