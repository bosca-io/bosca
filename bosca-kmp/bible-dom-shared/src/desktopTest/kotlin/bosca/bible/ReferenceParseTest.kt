package bosca.bible

import bosca.bible.style.IStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferenceParseTest {

    private fun book(usfm: String, long: String, short: String, abbreviation: String, chapters: Int) = Book(
        reference = Reference(usfm),
        name = Name(id = "book-${usfm.lowercase()}", long = long, short = short, abbreviation = abbreviation),
        chapters = (1..chapters).map { Chapter(Reference("$usfm.$it"), null) }
    )

    private val bible = object : IBible {
        override val metadata: IBibleMetadata get() = error("not needed")
        override val books = listOf(
            book("GEN", "Genesis", "Genesis", "Gen", 50),
            book("EXO", "Exodus", "Exodus", "Exod", 40),
            book("JDG", "Judges", "Judges", "Judg", 21),
            book("PSA", "Psalms", "Psalms", "Ps", 150),
            book("JUD", "Jude", "Jude", "Jud", 1),
        )
        override val styles: List<IStyle> = emptyList()
        override fun get(reference: Reference) = books.find { it.reference.bookUsfm == reference.bookUsfm }
    }

    private fun parse(human: String) = Reference.parse(bible, human).map { it.usfm }

    @Test
    fun aChapterIsFound() {
        assertEquals(listOf("GEN.1"), parse("Genesis 1"))
        assertEquals(listOf("GEN.1.1"), parse("Genesis 1:1"))
        assertEquals(listOf("GEN.1.1+GEN.1.2+GEN.1.3"), parse("Genesis 1:1-3"))
    }

    @Test
    fun theSameChapterOfDifferentBooksStaysApart() {
        assertEquals(listOf("GEN.3.15", "EXO.3.14"), parse("Genesis 3:15, Exodus 3:14"))
        assertEquals(listOf("GEN.1.1+GEN.1.5"), parse("Genesis 1:1, Genesis 1:5"))
    }

    @Test
    fun aWholeNameWinsOverAShorterNameThatBeginsIt() {
        assertEquals(listOf("JDG.5.1"), parse("Judges 5:1"))
        assertTrue(parse("Psst 23").isEmpty())
    }

    @Test
    fun aPluralNameMatchesAsASingular() {
        assertEquals(listOf("PSA.23"), parse("Psalm 23"))
    }
}
