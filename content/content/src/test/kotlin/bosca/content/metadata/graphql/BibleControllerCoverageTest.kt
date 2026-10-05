package bosca.content.metadata.graphql

import bosca.bible.Reference
import bosca.bible.bibleJson
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.IComponent
import bosca.bible.components.Text
import bosca.bible.components.VerseStart
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.BibleLanguage
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BibleControllerCoverageTest {

    private val bibleService = mockk<BibleService>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissionEvaluator = mockk<MetadataPermissionEvaluator>()
    private val controller = BibleController(bibleService, metadataService, metadataPermissionEvaluator)

    private val metadataId = UUID.random()

    private val bible = Bible(
        metadataId = metadataId,
        version = 1,
        systemId = "sys-1",
        variant = "variant-1",
        defaultVariant = true,
        name = "World English Bible",
        nameLocal = "World English Bible Local",
        description = "A public domain translation.",
        abbreviation = "WEB",
        abbreviationLocal = "WEB-L",
        styles = JsonObject(mapOf("color" to JsonPrimitive("red")))
    )

    private fun book(usfm: String, sort: Int = 0) = BibleBook(
        metadataId = metadataId,
        version = 1,
        variant = "variant-1",
        usfm = usfm,
        nameShort = "Gen",
        nameLong = "Genesis",
        abbreviation = "Ge",
        sort = sort
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

    // ---- Plain field resolvers ----

    @Test
    fun `systemId returns bible systemId`() {
        assertEquals("sys-1", controller.systemId(bible))
    }

    @Test
    fun `variant returns bible variant`() {
        assertEquals("variant-1", controller.variant(bible))
    }

    @Test
    fun `defaultVariant returns bible defaultVariant`() {
        assertTrue(controller.defaultVariant(bible))
    }

    @Test
    fun `enabled returns bible enabled state`() {
        assertTrue(controller.enabled(bible))
    }

    @Test
    fun `variants returns only enabled variants by default`() = runTest {
        val disabled = bible.copy(variant = "study", defaultVariant = false, enabled = false)
        coEvery { bibleService.getVariants(metadataId, 1) } returns listOf(bible, disabled)

        assertEquals(listOf(bible), controller.variants(null, bible, null))
    }

    @Test
    fun `variants returns disabled variants to metadata editors when requested`() = runTest {
        val authentication = mockk<AuthenticationContext>()
        val metadata = mockk<bosca.content.metadata.model.Metadata>()
        val disabled = bible.copy(variant = "study", defaultVariant = false, enabled = false)
        coEvery { bibleService.getVariants(metadataId, 1) } returns listOf(bible, disabled)
        coEvery { metadataService.getById(metadataId, 1) } returns metadata
        coEvery {
            metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        } returns Unit

        assertEquals(listOf(bible, disabled), controller.variants(authentication, bible, true))
        coVerify { metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT) }
    }

    @Test
    fun `requesting disabled variants fails when parent metadata is missing`() = runTest {
        coEvery { bibleService.getVariants(metadataId, 1) } returns listOf(bible)
        coEvery { metadataService.getById(metadataId, 1) } returns null

        assertFailsWith<NoSuchElementException> { controller.variants(null, bible, true) }
    }

    @Test
    fun `name returns bible name`() {
        assertEquals("World English Bible", controller.name(bible))
    }

    @Test
    fun `nameLocal returns bible nameLocal`() {
        assertEquals("World English Bible Local", controller.nameLocal(bible))
    }

    @Test
    fun `abbreviation returns bible abbreviation`() {
        assertEquals("WEB", controller.abbreviation(bible))
    }

    @Test
    fun `abbreviationLocal returns bible abbreviationLocal`() {
        assertEquals("WEB-L", controller.abbreviationLocal(bible))
    }

    @Test
    fun `description returns bible description`() {
        assertEquals("A public domain translation.", controller.description(bible))
    }

    @Test
    fun `styles returns bible styles`() {
        assertEquals(bible.styles, controller.styles(bible))
    }

    // ---- Suspend delegating resolvers ----

    @Test
    fun `languages delegates to service`() = runTest {
        val language = BibleLanguage(
            metadataId = metadataId,
            version = 1,
            variant = "variant-1",
            iso = "eng",
            name = "English",
            nameLocal = "English",
            script = "Latin",
            scriptCode = "Latn",
            scriptDirection = "ltr",
            sort = 0
        )
        coEvery { bibleService.getLanguages(bible) } returns listOf(language)

        val result = controller.languages(bible)

        assertEquals(1, result.size)
        assertSame(language, result[0])
    }

    @Test
    fun `books delegates to service`() = runTest {
        val books = listOf(book("GEN"), book("EXO", sort = 1))
        coEvery { bibleService.getBooks(bible) } returns books

        assertEquals(books, controller.books(bible))
    }

    // ---- chapter ----

    @Test
    fun `chapter returns null when no book matches the reference`() = runTest {
        // usfm GEN.1.1 -> bookUsfm GEN, but only EXO exists
        coEvery { bibleService.getBooks(bible) } returns listOf(book("EXO"))

        assertNull(controller.chapter(bible, "GEN.1.1"))
    }

    @Test
    fun `chapter returns BibleBookChapter when book matches`() = runTest {
        val genesis = book("GEN")
        val ch = chapter("GEN", "GEN.1", null)
        coEvery { bibleService.getBooks(bible) } returns listOf(book("EXO"), genesis)
        coEvery { bibleService.getChapter(genesis, "GEN.1.1") } returns ch

        val result = controller.chapter(bible, "GEN.1.1")

        assertEquals(genesis, result?.book)
        assertEquals(ch, result?.chapter)
    }

    // ---- book ----

    @Test
    fun `book returns matching book`() = runTest {
        val genesis = book("GEN")
        coEvery { bibleService.getBooks(bible) } returns listOf(genesis, book("EXO"))

        assertSame(genesis, controller.book(bible, "GEN"))
    }

    @Test
    fun `book returns null when no book matches`() = runTest {
        coEvery { bibleService.getBooks(bible) } returns listOf(book("EXO"))

        assertNull(controller.book(bible, "GEN"))
    }

    // ---- find ----

    @Test
    fun `find returns empty list when no references resolved`() = runTest {
        coEvery { bibleService.getReferences(bible, "nothing") } returns emptyList()
        coEvery { bibleService.getBooks(bible) } returns listOf(book("GEN"))

        assertTrue(controller.find(bible, "nothing").isEmpty())
    }

    @Test
    fun `find skips references whose book is not found`() = runTest {
        val reference = Reference("GEN.1.1")
        coEvery { bibleService.getReferences(bible, "Genesis 1:1") } returns listOf(reference)
        // book list has no GEN
        coEvery { bibleService.getBooks(bible) } returns listOf(book("EXO"))

        assertTrue(controller.find(bible, "Genesis 1:1").isEmpty())
    }

    @Test
    fun `find skips references whose chapter has no components`() = runTest {
        val reference = Reference("GEN.1.1")
        val genesis = book("GEN")
        val ch = chapter("GEN", "GEN.1", null) // components null -> getChapterComponents() == null
        coEvery { bibleService.getReferences(bible, "Genesis 1:1") } returns listOf(reference)
        coEvery { bibleService.getBooks(bible) } returns listOf(genesis)
        coEvery { bibleService.getChapter(genesis, reference.chapterUsfm) } returns ch

        assertTrue(controller.find(bible, "Genesis 1:1").isEmpty())
    }

    @Test
    fun `find returns result when book chapter and components resolve`() = runTest {
        val reference = Reference("GEN.1.1")
        val genesis = book("GEN")
        // A container holding a VerseStart matching the reference plus some text so filter keeps it.
        val components: IComponent = ComponentContainer(
            type = ContainerType.DIV,
            components = listOf(
                VerseStart(reference = Reference("GEN.1.1")),
                Text(text = "In the beginning", style = null)
            ),
            style = null
        )
        val ch = chapter("GEN", "GEN.1", components)

        coEvery { bibleService.getReferences(bible, "Genesis 1:1") } returns listOf(reference)
        coEvery { bibleService.getBooks(bible) } returns listOf(genesis)
        coEvery { bibleService.getChapter(genesis, reference.chapterUsfm) } returns ch
        coEvery { bibleService.getHuman(bible, reference) } returns "Gen 1:1"
        coEvery { bibleService.getHumanLong(bible, reference) } returns "Genesis 1:1"

        val results = controller.find(bible, "Genesis 1:1")

        assertEquals(1, results.size)
        val result = results[0]
        assertEquals(genesis, result.book)
        assertEquals(ch, result.chapter)
        assertEquals("Gen 1:1", result.human)
        assertEquals("Genesis 1:1", result.humanShort)
        assertEquals(reference, result.reference)
        // component is a JsonArray of filtered component json (one match for the single reference)
        assertTrue(result.component is JsonArray)
        assertEquals(1, (result.component as JsonArray).size)
    }
}
