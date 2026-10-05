package bosca.content.metadata.graphql

import bosca.bible.Reference
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.FindBibleResult
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class FindBibleResultControllerCoverageTest {

    private val controller = FindBibleResultController()

    private val metadataId = UUID.random()

    private fun book(usfm: String = "GEN") = BibleBook(
        metadataId = metadataId,
        version = 1,
        variant = "variant-1",
        usfm = usfm,
        nameShort = "Gen",
        nameLong = "Genesis",
        abbreviation = "Ge",
        sort = 0
    )

    private fun chapter(usfm: String = "GEN.1") = BibleChapter(
        metadataId = metadataId,
        version = 1,
        variant = "variant-1",
        bookUsfm = "GEN",
        usfm = usfm,
        components = null,
        sort = 0
    )

    private fun result(
        book: BibleBook = book(),
        chapter: BibleChapter? = chapter(),
        component: kotlinx.serialization.json.JsonElement? = JsonPrimitive("body"),
        human: String = "Genesis 1",
        humanShort: String = "Gen 1",
        reference: Reference = Reference("GEN.1")
    ) = FindBibleResult(
        book = book,
        chapter = chapter,
        component = component,
        human = human,
        humanShort = humanShort,
        reference = reference
    )

    // ---- book ----

    @Test
    fun `book returns the result book`() {
        val theBook = book(usfm = "EXO")
        val findResult = result(book = theBook)

        assertSame(theBook, controller.book(findResult))
    }

    // ---- chapter (both branches of the safe-call) ----

    @Test
    fun `chapter pairs book with chapter when chapter present`() {
        val theBook = book(usfm = "GEN")
        val theChapter = chapter(usfm = "GEN.1")
        val findResult = result(book = theBook, chapter = theChapter)

        val bookChapter = controller.chapter(findResult)

        assertSame(theBook, bookChapter?.book)
        assertSame(theChapter, bookChapter?.chapter)
    }

    @Test
    fun `chapter returns null when chapter absent`() {
        val findResult = result(chapter = null)

        assertNull(controller.chapter(findResult))
    }

    // ---- component (present and null) ----

    @Test
    fun `component returns the result component`() {
        val component = JsonPrimitive("some-component")
        val findResult = result(component = component)

        assertSame(component, controller.component(findResult))
    }

    @Test
    fun `component returns null when result component is null`() {
        val findResult = result(component = null)

        assertNull(controller.component(findResult))
    }

    // ---- reference ----

    @Test
    fun `reference builds a bible reference from usfm human and humanShort`() {
        val findResult = result(
            human = "Genesis 1:1",
            humanShort = "Gen 1:1",
            reference = Reference("GEN.1.1")
        )

        val reference = controller.reference(findResult)

        assertEquals("GEN.1.1", reference.usfm)
        assertEquals("Genesis 1:1", reference.human)
        assertEquals("Gen 1:1", reference.humanShort)
    }
}
