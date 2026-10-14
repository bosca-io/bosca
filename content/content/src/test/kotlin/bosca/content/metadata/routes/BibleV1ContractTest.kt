package bosca.content.metadata.routes

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
import bosca.bible.style.Style
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.model.toJson
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.documents.ContainerAttributes
import bosca.documents.ContainerNode
import bosca.documents.Content
import bosca.routes.Route
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BibleV1ContractTest {
    private val id = UUID.random()
    private val bibles = mockk<BibleService>()
    private val metadata = mockk<MetadataService>()
    private val documents = mockk<DocumentService>()
    private val permissions = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>()
    private val record = Metadata(id = id, version = 1, name = "Bible", type = MetadataType.STANDARD,
        contentType = "bosca/v-bible", contentLength = 0, languageTag = "eng", workflowStateId = "published")
    private val bible = Bible(metadataId = id, version = 1, systemId = "edition", variant = "default",
        defaultVariant = true, name = "Bible", nameLocal = "Bible", description = "Description",
        abbreviation = "B", abbreviationLocal = "B", styles = JsonNull)
    private val book = BibleBook(id, 1, "default", "JHN", "Jn", "John", "JHN", 0)
    private val reference = Reference("JHN.1")
    private val components = ComponentContainer(ContainerType.PARAGRAPH, listOf(
        VerseStart(Reference("JHN.1.1")), Text("First verse", Style("text")), VerseEnd(),
        VerseStart(Reference("JHN.1.2")), Text("Second verse", null), VerseEnd(),
    ), Style("paragraph"))
    private val attribution = BibleAttribution("Response copyright")
    private val chapter = BibleChapter(id, 1, "default", "JHN", "JHN.1", components.toJson(), 0,
        sourceId = UUID.random(), attribution = attribution, sourceVersion = 9,
        verseCount = 2, created = OffsetDateTime.now(),
        expires = OffsetDateTime.now().plusDays(1))
    private val json = Json {
        encodeDefaults = true
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
        }
    }
    private val chapterFields = setOf("metadataId", "version", "variant", "bookUsfm", "usfm", "components", "sort")
    private val verseFields = setOf("human", "humanLong", "usfm", "chapter", "content")

    private fun stubBible() {
        coEvery { metadata.getById(id) } returns record
        coEvery { metadata.find(any()) } returns listOf(record)
        coEvery { bibles.getBible(id, 1, null) } returns bible
        coEvery { bibles.getBooks(bible) } returns listOf(book)
        coEvery { bibles.getChapters(book) } returns listOf(chapter)
        coEvery { bibles.getChapter(book, any<String>()) } returns chapter
        coEvery { bibles.getChapter(bible, any<Reference>()) } returns chapter
        coEvery { bibles.getReferences(bible, any()) } returns listOf(Reference("JHN.1.2"))
        coEvery { bibles.getChapterContent(bible, any<Reference>(), BibleContentUse.DISPLAY) } returns
            BibleChapterContent(Chapter(reference, components), attribution)
    }

    private fun call(query: Map<String, String> = emptyMap(), paths: Map<String, String> = emptyMap()): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters.fromSingleValueMap(mapOf("language" to "en") + query)
        return mockk<ServerCall>(relaxed = true).also {
            every { it.request } returns request
            every { it.pathParameters } returns Parameters.fromSingleValueMap(mapOf(
                "id" to id.toString(), "bookUsfm" to "JHN", "chapterUsfm" to "JHN.1", "usfm" to "JHN",
            ) + paths)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> execute(route: Route<T>, call: ServerCall): T? {
        val method = route::class.declaredMemberFunctions.first { it.name == "execute" && it.parameters.size == 3 }
        method.isAccessible = true
        return method.callSuspend(route, call, authentication) as T?
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> encode(route: Route<T>, value: T): JsonElement {
        val method = route::class.declaredMemberFunctions.first { it.name == "serializer" }
        method.isAccessible = true
        return json.encodeToJsonElement(method.call(route) as KSerializer<T>, value)
    }

    @Test
    fun `v1 single chapter keeps its published chapter fields while v2 returns shared content`() = runTest {
        stubBible()
        val v1 = GetBibleBookChapter(metadata, permissions, bibles)
        val local = chapter.copy(sourceId = null, attribution = null, sourceVersion = null, expires = null)
        coEvery { bibles.getChapter(book, any<String>()) } returns local
        val old = encode(v1, assertNotNull(execute(v1, call()))).jsonObject
        assertTrue(old.keys.containsAll(chapterFields))
        assertEquals(components.toJson(), old.getValue("components"))
        coVerify(exactly = 0) { bibles.getChapterContent(any<BibleBook>(), any<String>(), any()) }

        coEvery { bibles.getChapterContent(book, "JHN.1", BibleContentUse.DISPLAY) } returns
            BibleChapterContent(Chapter(reference, components), attribution)
        val v2 = GetBibleBookChapterV2(metadata, permissions, bibles)
        val current = encode(v2, assertNotNull(execute(v2, call()))).jsonObject
        assertEquals(setOf("chapter", "attribution"), current.keys)
        assertEquals(components.toJson(), current.getValue("chapter").jsonObject.getValue("component"))
        assertTrue(current.getValue("chapter").toString().contains("Second verse"))
        assertTrue(current.getValue("attribution").toString().contains("Response copyright"))
        val restored = bibleJson.decodeFromJsonElement(BibleChapterContent.serializer(), current)
        assertEquals(reference, restored.chapter.reference)
        assertEquals(components.toJson(), restored.chapter[reference]?.toJson())
        assertEquals(attribution, restored.attribution)
        coVerify(exactly = 2) { permissions.verifyAllowed(authentication, record, PermissionAction.VIEW) }
    }

    @Test
    fun `v1 single chapter returns provider text with display checks`() = runTest {
        stubBible()
        // BibleService omits provider bodies and response metadata from getChapter.
        coEvery { bibles.getChapter(book, any<String>()) } returns
            chapter.copy(components = null, attribution = null, sourceVersion = null, expires = null)
        coEvery { bibles.getChapterContent(book, "JHN.1", BibleContentUse.DISPLAY) } returns
            BibleChapterContent(Chapter(reference, components), attribution)
        val route = GetBibleBookChapter(metadata, permissions, bibles)

        val value = encode(route, assertNotNull(execute(route, call()))).jsonObject

        assertTrue(value.keys.containsAll(chapterFields))
        assertEquals(components.toJson(), value.getValue("components"))
        coVerify(exactly = 1) { bibles.getChapterContent(book, "JHN.1", BibleContentUse.DISPLAY) }
    }

    @Test
    fun `v1 chapter lists keep their fields and omit component bodies`() = runTest {
        stubBible()
        val route = GetBibleBookChapters(metadata, permissions, bibles)
        val value = encode(route, assertNotNull(execute(route, call()))).jsonArray.single().jsonObject
        assertTrue(value.keys.containsAll(chapterFields))
        assertEquals(JsonNull, value.getValue("components"))
    }

    @Test
    fun `v1 chapter routes retain missing book results`() = runTest {
        stubBible()
        coEvery { bibles.getBooks(bible) } returns emptyList()
        assertNull(execute(GetBibleBookChapter(metadata, permissions, bibles), call()))
        assertEquals(emptyList(), execute(GetBibleBookChapters(metadata, permissions, bibles), call()))
    }

    @Test
    fun `v1 ID and language verse readers retain their fields and selected text while adding attribution`() = runTest {
        stubBible()
        val routes = listOf(
            GetBibleBookChapterVerses(metadata, permissions, bibles),
            GetBibleLanguageVerses(metadata, permissions, bibles),
        )
        for (route in routes) {
            val value = encode(route, assertNotNull(execute(route, call(mapOf("usfm" to "JHN.1.2"))))).jsonArray.single().jsonObject
            assertTrue(value.keys.containsAll(verseFields))
            assertTrue(value.getValue("chapter").jsonObject.keys.containsAll(chapterFields))
            assertTrue(value.getValue("content").toString().contains("Second verse"))
            assertFalse(value.getValue("content").toString().contains("First verse"))
            assertTrue(value.getValue("attribution").toString().contains("Response copyright"))
        }
        coVerify(exactly = 2) { bibles.getChapterContent(any<Bible>(), any<Reference>(), BibleContentUse.DISPLAY) }
    }

    @Test
    fun `v1 ID verse reader retains whole chapters and skips unavailable component bodies`() = runTest {
        stubBible()
        val route = GetBibleBookChapterVerses(metadata, permissions, bibles)
        val whole = assertNotNull(execute(route, call(mapOf("usfm" to "JHN.1")))).single()
        assertEquals(listOf(components.toJson()), whole.content)
        coEvery { bibles.getChapterContent(bible, any<Reference>(), BibleContentUse.DISPLAY) } returns
            BibleChapterContent(Chapter(reference, null))
        assertEquals(emptyList(), execute(route, call(mapOf("usfm" to "JHN.1"))))
        assertEquals(emptyList(), execute(GetBibleLanguageVerses(metadata, permissions, bibles), call(mapOf("usfm" to "JHN.1"))))
    }

    @Test
    fun `v1 reference search retains the content flag and supports references without bodies`() = runTest {
        stubBible()
        val route = FindBibleReferences(metadata, bibles, permissions)
        for (includeContent in listOf(true, false)) {
            val value = encode(route, assertNotNull(execute(route, call(mapOf(
                "human" to "John 1:2", "content" to includeContent.toString(),
            ))))).jsonArray.single().jsonObject
            assertTrue(value.keys.containsAll(verseFields))
            assertTrue(value.getValue("chapter").jsonObject.keys.containsAll(chapterFields))
            assertEquals(includeContent, value.getValue("content").jsonArray.isNotEmpty())
        }
        coEvery { bibles.getChapter(bible, any<Reference>()) } returns chapter.copy(components = null)
        assertEquals(1, assertNotNull(execute(route, call(mapOf("human" to "John 1:2", "content" to "false")))).size)
    }

    @Test
    fun `v1 document verses retain their fields and inherited references`() = runTest {
        stubBible()
        val parentId = UUID.random()
        val node = ContainerNode(attributes = ContainerAttributes(name = "BIBLE_REFERENCES", references = listOf("JHN.1.2")), content = emptyList())
        coEvery { metadata.getById(id) } returns record.copy(parentId = parentId)
        coEvery { metadata.getById(parentId) } returns record.copy(id = parentId)
        coEvery { documents.getDocument(id, 1) } returns null
        coEvery { documents.getDocument(parentId, 1) } returns Document(metadataId = parentId, version = 1,
            title = "Document", content = Content(document = bosca.documents.Document(content = listOf(node))))
        val route = GetDocumentVerses(metadata, bibles, permissions, documents, json)
        val value = encode(route, assertNotNull(execute(route, call()))).jsonArray.single().jsonObject
        assertTrue(value.keys.containsAll(verseFields))
        assertTrue(value.getValue("chapter").jsonObject.keys.containsAll(chapterFields))
        assertTrue(value.getValue("content").toString().contains("Second verse"))
        coVerify(exactly = 1) { bibles.getChapterContent(bible, reference, BibleContentUse.DISPLAY) }
    }
}
