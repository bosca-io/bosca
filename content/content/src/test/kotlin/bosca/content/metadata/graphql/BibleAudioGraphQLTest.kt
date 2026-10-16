package bosca.content.metadata.graphql

import bosca.bible.*
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.Metadata
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

class BibleAudioGraphQLTest {
    private val bibles = mockk<BibleService>()
    private val metadata = mockk<MetadataService>()
    private val permissions = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val id = UUID.random()
    private val audioId = UUID.random()
    private val bible = Bible(metadataId = id, version = 2, systemId = "publication", variant = "study", defaultVariant = true, name = "Bible", nameLocal = "Bible", description = "Description", abbreviation = "B", abbreviationLocal = "B", styles = JsonNull)
    private val item = Metadata(id = id, name = "Bible", type = bosca.content.metadata.model.MetadataType.STANDARD, contentType = "bosca/v-bible", contentLength = 0, languageTag = "eng", workflowStateId = "published")
    private val audio = BibleAudio(audioId, "Recording", "Local recording", "R", BibleAttribution("Copyright", "Citation"))
    private val chapter = BibleAudioChapter(audio, "GEN.1", "/api/v1/content/metadata/bible/audio?id=$id",
        BibleAudioContent("audio/mpeg", 15, listOf(BibleAudioTimecode("GEN.1.1", 0.0, 1.5))), audio.attribution)
    private lateinit var graphql: GraphQL

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } } }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("bible-audio-test") }
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } answers { ConnectionManager(pool) }
        provides<ConnectionPool> { pool }
        coEvery { bibles.getAudio(bible) } returns listOf(audio)
        coEvery { bibles.getAudioChapter(bible, audioId, "GEN.1") } returns chapter
        coEvery { metadata.getById(id) } returns item
        val wiring = RuntimeWiringBuilder().apply {
            scalar("UUID", ExtendedScalars.Uuid)
            scalar("Long", ExtendedScalars.Long)
            scalar("JSON", ExtendedScalars.Json)
            type("Query") { field("bible") { bible } }
            type(BibleControllerDispatcher(BibleController(bibles, metadata, permissions)).type)
            type(BibleAudioControllerDispatcher(BibleAudioController()).type)
            type(BibleAudioChapterControllerDispatcher(BibleAudioChapterController()).type)
            type(BibleAudioContentControllerDispatcher(BibleAudioContentController()).type)
            type(BibleAudioTimecodeControllerDispatcher(BibleAudioTimecodeController()).type)
            type(BibleAttributionControllerDispatcher(BibleAttributionController()).type)
        }.build()
        val sdl = requireNotNull(javaClass.getResourceAsStream("/graphql/metadata/bible.graphqls")).bufferedReader().use { it.readText() }
        graphql = GraphQL(ExecutableSchema.fromSdl("scalar UUID\nscalar Long\nscalar JSON\ntype Query { bible: Bible! }\n$sdl", wiring))
    }

    @AfterTest fun teardown() { ProviderRegistry.clear() }

    private suspend fun read() = graphql.execute(GraphQLRequest("""
        { bible { audio { id name nameLocal abbreviation attribution { copyright citation } }
          audioChapter(audioId: "$audioId", usfm: "GEN.1") {
            audio { id } usfm url attribution { copyright }
            content { contentType contentLength timecodes { usfm start end } }
          }
        } }
    """.trimIndent(), context = GraphQLContext(mapOf("authenticationContext" to auth))))

    @Test
    fun `generated reader resolvers expose recording descriptions timing attribution and an authorized route`() = runTest {
        val result = read()
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val data = assertNotNull(result.data).jsonObject.getValue("bible").jsonObject
        assertEquals("Local recording", data.getValue("audio").jsonArray.single().jsonObject.getValue("nameLocal").jsonPrimitive.content)
        val response = data.getValue("audioChapter").jsonObject
        assertEquals(chapter.url, response.getValue("url").jsonPrimitive.content)
        assertEquals("GEN.1.1", response.getValue("content").jsonObject.getValue("timecodes").jsonArray.single().jsonObject.getValue("usfm").jsonPrimitive.content)
        assertFalse(response.containsKey("fumsToken"))
        coVerify(exactly = 1) { permissions.verifyContentAllowed(auth, item, PermissionAction.VIEW) }
    }

    @Test
    fun `missing metadata and content denial prevent recording reads`() = runTest {
        coEvery { metadata.getById(id) } returns null
        assertTrue(read().errors.isNotEmpty())
        coEvery { metadata.getById(id) } returns item
        coEvery { permissions.verifyContentAllowed(auth, item, PermissionAction.VIEW) } throws IllegalStateException("denied")
        assertTrue(read().errors.isNotEmpty())
        coVerify(exactly = 0) { bibles.getAudioChapter(any(), any(), any()) }
    }
}
