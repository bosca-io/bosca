package bosca.bible

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferenceTest {

    @Test
    fun bookUsfmExtractsFirstPart() {
        val ref = Reference("GEN.1.1")
        assertEquals("GEN", ref.bookUsfm)
    }

    @Test
    fun bookUsfmSinglePart() {
        val ref = Reference("GEN")
        assertEquals("GEN", ref.bookUsfm)
    }

    @Test
    fun chapterUsfmExtractsBookAndChapter() {
        val ref = Reference("GEN.1.1")
        assertEquals("GEN.1", ref.chapterUsfm)
    }

    @Test
    fun chapterUsfmEmptyWhenNoChapter() {
        val ref = Reference("GEN")
        assertEquals("", ref.chapterUsfm)
    }

    @Test
    fun chapterExtractsChapterNumber() {
        val ref = Reference("GEN.3.16")
        assertEquals("3", ref.chapter)
    }

    @Test
    fun chapterEmptyWhenBookOnly() {
        val ref = Reference("GEN")
        assertEquals("", ref.chapter)
    }

    @Test
    fun numberExtractsVerseNumber() {
        val ref = Reference("GEN.1.5")
        assertEquals("5", ref.number)
    }

    @Test
    fun numberEmptyWhenNoVerse() {
        val ref = Reference("GEN.1")
        assertEquals("", ref.number)
    }

    @Test
    fun numberEmptyWhenBookOnly() {
        val ref = Reference("GEN")
        assertEquals("", ref.number)
    }

    @Test
    fun referencesReturnsSingleForSimpleUsfm() {
        val ref = Reference("GEN.1.1")
        assertEquals(1, ref.references.size)
        assertEquals("GEN.1.1", ref.references[0].usfm)
    }

    @Test
    fun referencesSplitsOnPlus() {
        val ref = Reference("GEN.1.1+GEN.1.2+GEN.1.3")
        assertEquals(3, ref.references.size)
        assertEquals("GEN.1.1", ref.references[0].usfm)
        assertEquals("GEN.1.2", ref.references[1].usfm)
        assertEquals("GEN.1.3", ref.references[2].usfm)
    }

    @Test
    fun plusOperatorCombinesReferences() {
        val ref1 = Reference("GEN.1.1")
        val ref2 = Reference("GEN.1.2")
        val combined = ref1 + ref2
        assertEquals("GEN.1.1+GEN.1.2", combined.usfm)
    }

    @Test
    fun equalityByUsfm() {
        val ref1 = Reference("GEN.1.1")
        val ref2 = Reference("GEN.1.1")
        assertEquals(ref1, ref2)
        assertEquals(ref1.hashCode(), ref2.hashCode())
    }

    @Test
    fun toStringReturnsUsfm() {
        val ref = Reference("PSA.23")
        assertEquals("PSA.23", ref.toString())
    }

    @Test
    fun toHumanBookOnly() {
        val ref = Reference("GEN")
        val human = ref.toHuman("Genesis")
        assertEquals("Genesis", human)
    }

    @Test
    fun toHumanChapterOnly() {
        val ref = Reference("GEN.1")
        val human = ref.toHuman("Genesis")
        assertEquals("Genesis 1", human)
    }

    @Test
    fun toHumanSingleVerse() {
        val ref = Reference("GEN.1.5")
        val human = ref.toHuman("Genesis")
        assertEquals("Genesis 1:5", human)
    }

    @Test
    fun toHumanVerseRange() {
        val ref = Reference("GEN.1.1+GEN.1.2+GEN.1.3")
        val human = ref.toHuman("Genesis")
        assertEquals("Genesis 1:1-3", human)
    }

    @Test
    fun toHumanWithIBook() {
        val name = Name(id = "GEN", long = "Genesis", short = "Gen", abbreviation = "Ge")
        val book = Book(
            reference = Reference("GEN"),
            name = name,
            chapters = emptyList()
        )
        val ref = Reference("GEN.1.1")
        val human = ref.toHuman(book)
        assertEquals("Genesis 1:1", human)
    }

    @Test
    fun toHumanFallsBackToShortName() {
        val name = Name(id = "GEN", long = "", short = "Gen", abbreviation = "Ge")
        val book = Book(
            reference = Reference("GEN"),
            name = name,
            chapters = emptyList()
        )
        val ref = Reference("GEN.1.1")
        val human = ref.toHuman(book)
        assertEquals("Gen 1:1", human)
    }
}
