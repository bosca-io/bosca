package bosca.content.metadata.graphql

import bosca.bible.*
import bosca.bible.components.*
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleBook
import bosca.content.metadata.model.BibleChapter
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.BibleService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.server.*
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.*
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

/** Exercises the published SDL and generated field dispatch with source-use rejection and exact verse filtering. */
@OptIn(bosca.di.annotation.InternalDI::class)
class BibleAiContextGraphQLTest {
    private val bibles = mockk<BibleService>()
    private val metadata = mockk<MetadataService>()
    private val permissions = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val id = UUID.random()
    private val bible = Bible(id, 2, "fixture", "reader", true, true, "Fixture Bible", "Fixture Bible", "", "FB", "FB", JsonNull)
    private val item = Metadata(id = id, name = "Fixture Bible", type = MetadataType.STANDARD,
        contentType = "bosca/v-bible", contentLength = 0, languageTag = "eng", workflowStateId = "published")
    private val book = BibleBook(id, 2, "reader", "JHN", "John", "John", "Jn", 0)
    private val verse = Reference("JHN.3.16")
    private val chapter = BibleChapter(id, 2, "reader", "JHN", "JHN.3", null, 0)
    private val content = BibleChapterContent(Chapter(Reference("JHN.3"), ComponentContainer(ContainerType.PARAGRAPH, listOf(
        VerseStart(Reference("JHN.3.15")), Text("Unrequested fixture text.", null), VerseEnd(),
        VerseStart(verse), Text("Exact selected fixture text.", null), VerseEnd(),
        VerseStart(Reference("JHN.3.17")), Text("Following fixture text.", null), VerseEnd(),
    ), null)), BibleAttribution("Fixture copyright", "Fixture citation", "https://example.com/fixture"))
    private lateinit var graphql: GraphQL

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } } }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("bible-ai-context-test") }
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } answers { ConnectionManager(pool) }
        provides<ConnectionPool> { pool }
        coEvery { metadata.getById(id, 2) } returns item
        coEvery { bibles.getReferences(bible, "John 3:16") } returns listOf(verse)
        coEvery { bibles.getHumanLong(bible, verse) } returns "John 3:16"
        coEvery { bibles.getHuman(bible, verse) } returns "Jn 3:16"
        coEvery { bibles.getBooks(bible) } returns listOf(book)
        coEvery { bibles.getChapter(book, "JHN.3") } returns chapter
        coEvery { bibles.getChapterContent(book, "JHN.3", BibleContentUse.AI_CONTEXT) } returns content
        val wiring = RuntimeWiringBuilder().apply {
            scalar("UUID", ExtendedScalars.Uuid)
            scalar("Long", ExtendedScalars.Long)
            scalar("JSON", ExtendedScalars.Json)
            type("Query") { field("bible") { bible } }
            type(BibleControllerDispatcher(BibleController(bibles, metadata, permissions)).type)
            type(FindBibleResultControllerDispatcher(FindBibleResultController()).type)
            type(BibleReferenceControllerDispatcher(BibleReferenceController()).type)
            type(BibleAttributionControllerDispatcher(BibleAttributionController()).type)
        }.build()
        val sdl = requireNotNull(javaClass.getResourceAsStream("/graphql/metadata/bible.graphqls")).bufferedReader().use { it.readText() }
        graphql = GraphQL(ExecutableSchema.fromSdl("scalar UUID\nscalar Long\nscalar JSON\ntype Query { bible: Bible! }\n$sdl", wiring))
    }

    @AfterTest fun teardown() { ProviderRegistry.clear() }

    private suspend fun execute(fields: String) = graphql.execute(GraphQLRequest(
        "{ bible { $fields } }", context = GraphQLContext(mapOf("authenticationContext" to auth)),
    ))

    private suspend fun read() = execute("""
        metadataId version variant
        aiContext(human: "John 3:16") {
            reference { usfm human humanShort }
            component
            attribution { copyright citation informationUrl }
        }
    """.trimIndent())

    @Test
    fun `AI passage reads preserve exact selected text reference edition and attribution`() = runTest {
        val result = read()
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val data = assertNotNull(result.data).jsonObject.getValue("bible").jsonObject
        assertEquals("reader", data.getValue("variant").jsonPrimitive.content)
        assertEquals(2, data.getValue("version").jsonPrimitive.int)
        val passage = data.getValue("aiContext").jsonArray.single().jsonObject
        assertEquals("JHN.3.16", passage.getValue("reference").jsonObject.getValue("usfm").jsonPrimitive.content)
        assertContains(passage.getValue("component").toString(), "Exact selected fixture text.")
        assertFalse(passage.getValue("component").toString().contains("Unrequested"))
        assertFalse(passage.getValue("component").toString().contains("Following"))
        assertEquals("Fixture citation", passage.getValue("attribution").jsonObject.getValue("citation").jsonPrimitive.content)
        coVerify(exactly = 1) { permissions.verifyContentAllowed(auth, item, PermissionAction.VIEW) }
        coVerify(exactly = 1) { bibles.getChapterContent(book, "JHN.3", BibleContentUse.AI_CONTEXT) }
        coVerify(exactly = 0) { bibles.getChapterContent(any<BibleBook>(), any<String>(), BibleContentUse.DISPLAY) }
    }

    @Test
    fun `content access denial or missing metadata prevents fetching passages`() = runTest {
        coEvery { metadata.getById(id, 2) } returns null
        assertTrue(read().errors.isNotEmpty())
        coEvery { metadata.getById(id, 2) } returns item
        coEvery { permissions.verifyContentAllowed(auth, item, PermissionAction.VIEW) } throws IllegalStateException("denied")
        assertTrue(read().errors.isNotEmpty())
        coVerify(exactly = 0) { bibles.getChapterContent(any<BibleBook>(), any<String>(), any()) }
    }

    @Test
    fun `source AI use denial fails without display fallback or quoted content`() = runTest {
        coEvery { bibles.getChapterContent(book, "JHN.3", BibleContentUse.AI_CONTEXT) } throws IllegalStateException("AI_CONTEXT denied")
        val result = read()
        assertTrue(result.errors.isNotEmpty())
        assertFalse(result.data.toString().contains("Exact selected"))
        coVerify(exactly = 0) { bibles.getChapterContent(any<BibleBook>(), any<String>(), BibleContentUse.DISPLAY) }
    }

    @Test
    fun `reference resolution does not retrieve content and unknown references stay empty`() = runTest {
        coEvery { bibles.getReferences(bible, "Unknown") } returns emptyList()
        val result = execute("""references(human: "John 3:16") { usfm human } missing: references(human: "Unknown") { usfm }""")
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val data = assertNotNull(result.data).jsonObject.getValue("bible").jsonObject
        assertEquals("JHN.3.16", data.getValue("references").jsonArray.single().jsonObject.getValue("usfm").jsonPrimitive.content)
        assertTrue(data.getValue("missing").jsonArray.isEmpty())
        coVerify(exactly = 0) { bibles.getChapterContent(any<BibleBook>(), any<String>(), any()) }
    }

    @Test
    fun `separate references retain only their own selected components`() = runTest {
        val following = Reference("JHN.3.17")
        coEvery { bibles.getReferences(bible, "John 3:16, John 3:17") } returns listOf(verse, following)
        coEvery { bibles.getHumanLong(bible, following) } returns "John 3:17"
        coEvery { bibles.getHuman(bible, following) } returns "Jn 3:17"
        val result = execute("""aiContext(human: "John 3:16, John 3:17") { reference { usfm } component }""")
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val passages = assertNotNull(result.data).jsonObject.getValue("bible").jsonObject.getValue("aiContext").jsonArray
        assertEquals(2, passages.size)
        val first = passages[0].jsonObject.getValue("component").toString()
        val second = passages[1].jsonObject.getValue("component").toString()
        assertContains(first, "Exact selected fixture text.")
        assertFalse(first.contains("Following"))
        assertContains(second, "Following fixture text.")
        assertFalse(second.contains("Exact selected"))
    }

    @Test
    fun `whole chapter references retain the full retrieved chapter`() = runTest {
        val reference = Reference("JHN.3")
        coEvery { bibles.getReferences(bible, "John 3") } returns listOf(reference)
        coEvery { bibles.getHumanLong(bible, reference) } returns "John 3"
        coEvery { bibles.getHuman(bible, reference) } returns "Jn 3"
        val result = execute("""aiContext(human: "John 3") { component }""")
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        for (text in listOf("Unrequested fixture text.", "Exact selected fixture text.", "Following fixture text.")) {
            assertContains(result.data.toString(), text)
        }
    }

    @Test
    fun `book-only references require an explicit chapter before content retrieval`() = runTest {
        coEvery { bibles.getReferences(bible, "John") } returns listOf(Reference("JHN"))
        val result = execute("""aiContext(human: "John") { component }""")
        assertTrue(result.errors.isNotEmpty())
        coVerify(exactly = 0) { bibles.getChapterContent(any<BibleBook>(), any<String>(), any()) }
    }

    @Test
    fun `display search skips book-only references and still resolves the rest`() = runTest {
        coEvery { bibles.getReferences(bible, "John; John 3:16") } returns listOf(Reference("JHN"), verse)
        coEvery { bibles.getChapterContent(book, "JHN.3", BibleContentUse.DISPLAY) } returns content
        val result = execute("""find(human: "John; John 3:16") { reference { usfm } component }""")
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val found = assertNotNull(result.data).jsonObject.getValue("bible").jsonObject.getValue("find").jsonArray
        assertEquals(listOf("JHN.3.16"), found.map { it.jsonObject.getValue("reference").jsonObject.getValue("usfm").jsonPrimitive.content })
        assertContains(found.toString(), "Exact selected fixture text.")
        coVerify(exactly = 0) { bibles.getChapter(book, "") }
    }
}
