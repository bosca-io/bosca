package bosca.content.metadata

import bosca.bible.BibleAttribution
import bosca.bible.BibleChapterContent
import bosca.bible.BibleContentUse
import bosca.bible.Chapter
import bosca.bible.Reference
import bosca.bible.bibleJson
import bosca.bible.components.ComponentContainer
import bosca.bible.components.ContainerType
import bosca.bible.components.Text
import bosca.bible.components.VerseEnd
import bosca.bible.components.VerseStart
import bosca.content.metadata.graphql.BibleChapterController
import bosca.content.metadata.graphql.BibleController
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleBookChapter
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.FindBibleResult
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.routes.ChapterContent
import bosca.content.metadata.routes.GetBibleBookChapterVerses
import bosca.content.metadata.routes.GetBibleBookChapterV2
import bosca.content.metadata.routes.GetBibleLanguageVerses
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BibleProviderReaderTest {
    private val id = UUID.random()
    private val bibles = mockk<BibleService>()
    private val metadata = mockk<MetadataService>()
    private val permissions = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>()
    private val bible = Bible(metadataId = id, version = 3, systemId = "edition", variant = "study",
        defaultVariant = true, name = "Bible", nameLocal = "Bible", description = "Description",
        abbreviation = "B", abbreviationLocal = "B", styles = JsonNull)
    private val book = BibleBook(id, 3, "study", "JHN", "Jn", "John", "JHN", 0)
    private val chapter = BibleChapter(id, 3, "study", "JHN", "JHN.1", null, 0)
    private val reference = Reference("JHN.1")
    private val attribution = BibleAttribution("Response copyright", "Response citation", "https://example.com/rights")
    private val content = BibleChapterContent(Chapter(reference, ComponentContainer(
        ContainerType.PARAGRAPH,
        listOf(
            VerseStart(Reference("JHN.1.1")), Text("Provider first verse", null), VerseEnd(),
            VerseStart(Reference("JHN.1.2")), Text("Provider second verse", null), VerseEnd(),
        ), null,
    )), attribution)
    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }

    private fun stubContent() {
        coEvery { bibles.getChapter(book, "JHN.1") } returns chapter
        coEvery { bibles.getChapter(bible, reference) } returns chapter
        coEvery { bibles.getChapterContent(book, "JHN.1", BibleContentUse.DISPLAY) } returns content
        coEvery { bibles.getChapterContent(bible, reference, BibleContentUse.DISPLAY) } returns content
        coEvery { bibles.getBooks(bible) } returns listOf(book)
    }

    @Test
    fun `GraphQL chapter reads provider components and verses when the stored projection has no text`() = runTest {
        stubContent()
        val controller = BibleChapterController(bibles)
        val source = BibleBookChapter(book, chapter)

        assertTrue(assertNotNull(controller.component(source)).toString().contains("Provider first verse"))
        assertEquals(listOf("JHN.1.1", "JHN.1.2"), controller.verses(source).map { it.usfm })
        coVerify(exactly = 2) { bibles.getChapterContent(book, "JHN.1", BibleContentUse.DISPLAY) }
        coVerify(exactly = 0) { bibles.getBible(any(), any(), any()) }
    }

    @Test
    fun `GraphQL reference search keeps selected provider verses and response attribution`() = runTest {
        stubContent()
        val selected = Reference("JHN.1.2")
        coEvery { bibles.getReferences(bible, "John 1:2") } returns listOf(selected)
        coEvery { bibles.getHuman(bible, selected) } returns "Jn 1:2"
        coEvery { bibles.getHumanLong(bible, selected) } returns "John 1:2"

        val result = BibleController(bibles, metadata, permissions).find(bible, "John 1:2").single()

        val encoded = json.encodeToJsonElement(FindBibleResult.serializer(), result).jsonObject
        assertTrue(encoded.getValue("component").toString().contains("Provider second verse"))
        assertFalse(encoded.getValue("component").toString().contains("Provider first verse"))
        assertAttribution(encoded.getValue("attribution").jsonObject)
        coVerify { bibles.getChapterContent(book, "JHN.1", BibleContentUse.DISPLAY) }
    }

    @Test
    fun `ID based REST reader selects provider verses and preserves attribution`() = runTest {
        stubContent()
        val result = GetBibleBookChapterVerses.getChapterVerses(Reference("JHN.1.2"), bible, bibles).single()

        val encoded = json.encodeToJsonElement(ChapterContent.serializer(), result).jsonObject
        assertTrue(encoded.getValue("content").toString().contains("Provider second verse"))
        assertFalse(encoded.getValue("content").toString().contains("Provider first verse"))
        assertEquals(JsonNull, encoded.getValue("chapter").jsonObject["components"] ?: JsonNull)
        assertAttribution(encoded.getValue("attribution").jsonObject)
        assertFalse(encoded.toString().contains("fums"))
        assertFalse(encoded.toString().contains("apiKey"))
        coVerify { bibles.getChapterContent(bible, reference, BibleContentUse.DISPLAY) }
    }

    @Test
    fun `language based REST reader returns a whole provider chapter with its exact variant attribution`() = runTest {
        stubContent()
        val record = Metadata(id = id, version = 3, name = "Bible", type = MetadataType.STANDARD,
            contentType = "bosca/v-bible", contentLength = 0, languageTag = "eng", workflowStateId = "published")
        coEvery { metadata.find(any()) } returns listOf(record)
        coEvery { bibles.getBible(id, 3, "study") } returns bible
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(mapOf(
            "usfm" to listOf("JHN.1"), "language" to listOf("en"), "variant" to listOf("study"),
        ))
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        val route = GetBibleLanguageVerses(metadata, permissions, bibles)
        val execute = route::class.declaredMemberFunctions.first { it.name == "execute" && it.parameters.size == 3 }
        execute.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val result = (execute.callSuspend(route, call, authentication) as List<ChapterContent>).single()

        val encoded = json.encodeToJsonElement(ChapterContent.serializer(), result).jsonObject
        assertTrue(encoded.getValue("content").toString().contains("Provider first verse"))
        assertTrue(encoded.getValue("content").toString().contains("Provider second verse"))
        assertAttribution(encoded.getValue("attribution").jsonObject)
        coVerify { permissions.verifyAllowed(authentication, record, PermissionAction.VIEW) }
        coVerify(exactly = 1) { bibles.getBible(id, 3, "study") }
    }

    @Test
    fun `single chapter REST reader returns shared chapter content and attribution`() = runTest {
        stubContent()
        val record = Metadata(id = id, version = 3, name = "Bible", type = MetadataType.STANDARD,
            contentType = "bosca/v-bible", contentLength = 0, languageTag = "eng", workflowStateId = "published")
        coEvery { metadata.getById(id) } returns record
        coEvery { bibles.getBible(id, 3, "study") } returns bible
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(mapOf("variant" to listOf("study")))
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(mapOf(
            "id" to listOf(id.toString()), "bookUsfm" to listOf("JHN"), "chapterUsfm" to listOf("JHN.1"),
        ))
        val route = GetBibleBookChapterV2(metadata, permissions, bibles)
        val execute = route::class.declaredMemberFunctions.first { it.name == "execute" && it.parameters.size == 3 }
        execute.isAccessible = true

        val response = execute.callSuspend(route, call, authentication) as JsonElement
        val result = bibleJson.decodeFromJsonElement(BibleChapterContent.serializer(), response)

        assertEquals(attribution, result.attribution)
        assertTrue(response.toString().contains("Provider first verse"))
        coVerify { permissions.verifyAllowed(authentication, record, PermissionAction.VIEW) }
    }

    @Test
    fun `display use denial propagates through the GraphQL chapter reader`() = runTest {
        stubContent()
        val denied = IllegalStateException("Display use is not allowed")
        coEvery { bibles.getChapterContent(book, "JHN.1", BibleContentUse.DISPLAY) } throws denied

        assertSame(denied, assertFailsWith<IllegalStateException> {
            BibleChapterController(bibles).component(BibleBookChapter(book, chapter))
        })
    }

    @Test
    fun `REST missing verse selection retains the chapter attribution`() = runTest {
        stubContent()

        val selected = GetBibleBookChapterVerses.getChapterVerses(Reference("JHN.1.3"), bible, bibles).single()

        assertEquals(listOf(JsonNull), selected.content)
        assertEquals(attribution, selected.attribution)
        assertEquals("JHN.1.3", selected.usfm)
    }

    @Test
    fun `GraphQL reference search keeps content and attribution separate across chapters`() = runTest {
        stubContent()
        val references = listOf(Reference("JHN.1.1"), Reference("JHN.2.1"))
        val secondAttribution = BibleAttribution("Second chapter copyright")
        val second = BibleChapterContent(Chapter(Reference("JHN.2"), ComponentContainer(ContainerType.PARAGRAPH,
            listOf(VerseStart(references[1]), Text("Second chapter text", null), VerseEnd()), null)), secondAttribution)
        coEvery { bibles.getReferences(bible, "John 1:1; 2:1") } returns references
        coEvery { bibles.getChapter(book, "JHN.2") } returns chapter.copy(usfm = "JHN.2")
        coEvery { bibles.getChapterContent(book, "JHN.2", BibleContentUse.DISPLAY) } returns second
        coEvery { bibles.getHuman(bible, any()) } answers { secondArg<Reference>().toHuman("Jn") }
        coEvery { bibles.getHumanLong(bible, any()) } answers { secondArg<Reference>().toHuman("John") }

        val results = BibleController(bibles, metadata, permissions).find(bible, "John 1:1; 2:1")

        assertEquals(2, results.size)
        assertTrue(results[0].component.toString().contains("Provider first verse"))
        assertFalse(results[0].component.toString().contains("Second chapter text"))
        assertEquals(attribution, results[0].attribution)
        assertTrue(results[1].component.toString().contains("Second chapter text"))
        assertFalse(results[1].component.toString().contains("Provider first verse"))
        assertEquals(secondAttribution, results[1].attribution)
    }

    @Test
    fun `locally imported content remains readable without attribution`() = runTest {
        stubContent()
        val local = content.copy(attribution = null)
        coEvery { bibles.getChapterContent(book, "JHN.1", BibleContentUse.DISPLAY) } returns local
        coEvery { bibles.getChapterContent(bible, reference, BibleContentUse.DISPLAY) } returns local
        val controller = BibleChapterController(bibles)

        assertTrue(assertNotNull(controller.component(BibleBookChapter(book, chapter))).toString().contains("Provider first verse"))
        assertNull(controller.attribution(BibleBookChapter(book, chapter)))
        val selected = GetBibleBookChapterVerses.getChapterVerses(reference, bible, bibles).single()
        assertNull(selected.attribution)
        assertEquals(1, selected.content.size)
    }

    @Test
    fun `REST reader propagates display use denial without returning an empty result`() = runTest {
        stubContent()
        val denied = IllegalStateException("Display use is not allowed")
        coEvery { bibles.getChapterContent(bible, reference, BibleContentUse.DISPLAY) } throws denied

        assertSame(denied, assertFailsWith<IllegalStateException> {
            GetBibleBookChapterVerses.getChapterVerses(reference, bible, bibles)
        })
    }

    @Test
    fun `cancellation propagates through the REST reader`() = runTest {
        stubContent()
        val cancelled = CancellationException("Request cancelled")
        coEvery { bibles.getChapterContent(bible, reference, BibleContentUse.DISPLAY) } throws cancelled

        assertSame(cancelled, assertFailsWith<CancellationException> {
            GetBibleBookChapterVerses.getChapterVerses(reference, bible, bibles)
        })
    }

    private fun assertAttribution(encoded: kotlinx.serialization.json.JsonObject) {
        assertEquals(attribution.copyright, encoded.getValue("copyright").jsonPrimitive.content)
        assertEquals(attribution.citation, encoded.getValue("citation").jsonPrimitive.content)
        assertEquals(attribution.informationUrl, encoded.getValue("informationUrl").jsonPrimitive.content)
    }
}
