package bosca.content.metadata.graphql

import bosca.bible.Reference
import bosca.bible.bibleJson
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.bible.components.VerseStart
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleBookChapter
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.service.BibleService
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BibleChapterControllerCoverageTest {

    private val bibleService = mockk<BibleService>()
    private val controller = BibleChapterController(bibleService)

    private val metadataId = UUID.random()

    private fun book(usfm: String) = BibleBook(
        metadataId = metadataId,
        version = 1,
        variant = "variant-1",
        usfm = usfm,
        nameShort = "Gen",
        nameLong = "Genesis",
        abbreviation = "Ge",
        sort = 0
    )

    private fun chapter(
        bookUsfm: String,
        usfm: String,
        components: IComponent?
    ) = BibleChapter(
        metadataId = metadataId,
        version = 1,
        variant = "variant-1",
        bookUsfm = bookUsfm,
        usfm = usfm,
        components = components?.let { bibleJson.encodeToJsonElement<IComponent>(it) },
        sort = 0
    )

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    // ---- reference ----

    @Test
    fun `reference builds human readable references from book names and chapter usfm`() {
        val bookChapter = BibleBookChapter(
            book = book("GEN"),
            chapter = chapter("GEN", "GEN.1", null)
        )

        val result = controller.reference(bookChapter)

        assertEquals("GEN.1", result.usfm)
        // usfm "GEN.1".split('.').last() == "1"
        assertEquals("Genesis 1", result.human)
        assertEquals("Gen 1", result.humanShort)
    }

    // ---- component ----

    @Test
    fun `component delegates to service and returns chapter components`() = runTest {
        val genesis = book("GEN")
        val components: IComponent = ComponentContainer(
            type = ContainerType.DIV,
            components = listOf(Text(text = "In the beginning", style = null)),
            style = null
        )
        val ch = chapter("GEN", "GEN.1", components)
        val bookChapter = BibleBookChapter(book = genesis, chapter = ch)
        // The service-returned chapter carries the components element that should be surfaced.
        val serviceChapter = chapter("GEN", "GEN.1", components)

        coEvery { bibleService.getChapter(genesis, "GEN.1") } returns serviceChapter

        val result = controller.component(bookChapter)

        assertSame(serviceChapter.components, result)
    }

    @Test
    fun `component returns null when service chapter has no components`() = runTest {
        val genesis = book("GEN")
        val ch = chapter("GEN", "GEN.1", null)
        val bookChapter = BibleBookChapter(book = genesis, chapter = ch)
        val serviceChapter = chapter("GEN", "GEN.1", null)

        coEvery { bibleService.getChapter(genesis, "GEN.1") } returns serviceChapter

        assertNull(controller.component(bookChapter))
    }

    // ---- verses ----

    @Test
    fun `verses returns empty list when chapter has no components`() {
        val bookChapter = BibleBookChapter(
            book = book("GEN"),
            chapter = chapter("GEN", "GEN.1", null)
        )

        assertTrue(controller.verses(bookChapter).isEmpty())
    }

    @Test
    fun `verses maps each found verse to a human readable reference`() {
        val components: IComponent = ComponentContainer(
            type = ContainerType.DIV,
            components = listOf(
                VerseStart(reference = Reference("GEN.1.1"), style = null),
                Text(text = "In the beginning", style = null),
                VerseStart(reference = Reference("GEN.1.2"), style = null),
                Text(text = "And the earth", style = null)
            ),
            style = null
        )
        val bookChapter = BibleBookChapter(
            book = book("GEN"),
            chapter = chapter("GEN", "GEN.1", components)
        )

        val result = controller.verses(bookChapter)

        assertEquals(2, result.size)

        // Reference("GEN.1.1").number == "1"; chapter usfm "GEN.1".split('.').last() == "1"
        assertEquals("GEN.1.1", result[0].usfm)
        assertEquals("Genesis 1:1", result[0].human)
        assertEquals("Gen 1:1", result[0].humanShort)

        assertEquals("GEN.1.2", result[1].usfm)
        assertEquals("Genesis 1:2", result[1].human)
        assertEquals("Gen 1:2", result[1].humanShort)
    }

    @Test
    fun `verses returns empty list when components contain no verse starts`() {
        val components: IComponent = ComponentContainer(
            type = ContainerType.DIV,
            components = listOf(Text(text = "No verses here", style = null)),
            style = null
        )
        val bookChapter = BibleBookChapter(
            book = book("GEN"),
            chapter = chapter("GEN", "GEN.1", components)
        )

        assertTrue(controller.verses(bookChapter).isEmpty())
    }

    // Guards that our chapter helper's component encoding is a valid non-null JsonElement so the
    // decodeFromJsonElement path in verses() is genuinely exercised (not short-circuited by null).
    @Test
    fun `chapter helper encodes components to a non null json element`() {
        val components: IComponent = ComponentContainer(
            type = ContainerType.DIV,
            components = listOf(Text(text = "x", style = null)),
            style = null
        )
        val ch = chapter("GEN", "GEN.1", components)

        assertTrue(ch.components != null)
    }
}
