package bosca.content.metadata.graphql

import bosca.bible.BibleAttribution
import bosca.bible.BibleAudio
import bosca.bible.BibleContentUse
import bosca.bible.BibleLanguage
import bosca.bible.providers.model.AddProviderAudioInput
import bosca.bible.providers.model.AddProviderBibleInput
import bosca.bible.providers.model.BibleCatalog
import bosca.bible.providers.model.BibleProviderConfiguration
import bosca.bible.providers.model.BibleProviderConfigurationInput
import bosca.bible.providers.model.BibleSource
import bosca.bible.providers.model.BibleSourceKind
import bosca.bible.providers.service.BibleProviderService
import bosca.content.graphql.Content
import bosca.content.graphql.ContentController
import bosca.content.graphql.ContentControllerDispatcher
import bosca.content.graphql.ContentMutation
import bosca.content.graphql.ContentMutationController
import bosca.content.graphql.ContentMutationControllerDispatcher
import bosca.content.metadata.model.Bible
import bosca.content.metadata.service.BibleService
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.server.ExecutableSchema
import bosca.graphql.server.ExtendedScalars
import bosca.graphql.server.GraphQL
import bosca.graphql.server.GraphQLContext
import bosca.graphql.server.GraphQLRequest
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.Called
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Executes the published SDL and KSP-generated resolvers, including Content namespace accessors. */
class BibleProvidersGraphQLTest {
    private val providers = mockk<BibleProviderService>()
    private val bibles = mockk<BibleService>()
    private val groups = mockk<GroupEvaluator>()
    private val authentication = mockk<AuthenticationContext>()
    private val id = UUID.random()
    private val metadataId = UUID.random()
    private val sourceId = UUID.random()
    private val configuration = BibleProviderConfiguration(id, "Provider", "API_BIBLE", true, true)
    private val attribution = BibleAttribution("Copyright", "Citation", "https://example.com/license")
    private val language = BibleLanguage("eng", "English", "English", "Latin", "Latn", "LTR")
    private val catalog = BibleCatalog("edition", "publication", BibleSourceKind.TEXT, "Bible", "Local Bible", "B", "LB",
        "Description", listOf(language), attribution, "text-publication")
    private val source = BibleSource(sourceId, id, "API_BIBLE", "edition", metadataId, 3, "study", BibleSourceKind.TEXT,
        "Bible", "Local Bible", "B", attribution, setOf(BibleContentUse.DISPLAY, BibleContentUse.AI_CONTEXT), 7)
    private val bible = Bible(metadataId, 3, "publication", "study", true, true, "Bible", "Local Bible", "Description", "B", "LB", JsonNull)
    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }
    private lateinit var graphql: GraphQL

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("bible-provider-graphql-test") }
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } answers { ConnectionManager(pool) }
        provides<ConnectionPool> { pool }
        every { groups.verifyHasAdminGroup(authentication) } just Runs
        val content = ContentController(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), groups)
        val wiring = RuntimeWiringBuilder().apply {
            scalar("UUID", ExtendedScalars.Uuid)
            scalar("Long", ExtendedScalars.Long)
            scalar("DateTime", bosca.graphql.scalars.DateTime.Type)
            scalar("JSON", ExtendedScalars.Json)
            type("Query") { field("content") { Content } }
            type("Mutation") { field("content") { ContentMutation } }
            type("Content") {
                field("bibleProviders", ContentControllerDispatcher(content).type.fieldResolvers.getValue("bibleProviders"))
            }
            type("ContentMutation") {
                field("bibleProviders", ContentMutationControllerDispatcher(ContentMutationController()).type.fieldResolvers.getValue("bibleProviders"))
            }
            type(BibleProvidersControllerDispatcher(BibleProvidersController(providers, groups)).type)
            type(BibleProvidersMutationControllerDispatcher(BibleProvidersMutationController(providers, bibles, groups)).type)
            type(BibleProviderConfigurationControllerDispatcher(BibleProviderConfigurationController()).type)
            type(BibleCatalogControllerDispatcher(BibleCatalogController()).type)
            type(BibleSourceControllerDispatcher(BibleSourceController()).type)
            type(BibleAudioControllerDispatcher(BibleAudioController()).type)
            type(BibleLanguageControllerDispatcher(BibleLanguageController()).type)
            type(BibleAttributionControllerDispatcher(BibleAttributionController()).type)
            type(BibleControllerDispatcher(BibleController(bibles, mockk(), mockk())).type)
        }.build()
        val root = """
            scalar UUID
            scalar Long
            scalar DateTime
            scalar JSON
            type Query { content: Content! }
            type Mutation { content: ContentMutation! }
            type Content { unused: Boolean }
            type ContentMutation { unused: Boolean }
        """.trimIndent()
        graphql = GraphQL(ExecutableSchema.fromSdl(root + "\n" + schema("bible-providers") + "\n" + schema("bible"), wiring))
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun schema(name: String) = assertNotNull(javaClass.getResourceAsStream("/graphql/metadata/$name.graphqls"))
        .bufferedReader().use { it.readText() }

    private suspend fun execute(query: String, variables: Map<String, Any?> = emptyMap()) = graphql.execute(GraphQLRequest(
        query, variables = variables, context = GraphQLContext(mapOf("authenticationContext" to authentication)),
    ))

    @Test
    fun `queries return catalog languages attribution sources and exact audio targets through generated bindings`() = runTest {
        every { providers.getProviders() } returns listOf("API_BIBLE")
        coEvery { providers.getConfigurations() } returns listOf(configuration)
        coEvery { providers.getCatalog(id, BibleSourceKind.TEXT, null, 0, 20) } returns listOf(catalog)
        coEvery { providers.getSources(id) } returns listOf(source)
        coEvery { providers.getAudioTargets(id, "recording") } returns listOf(bible)
        val result = execute("""
            { content { bibleProviders {
                providers
                configurations { id name provider enabled hasApiKey }
                catalog(configurationId: "$id", kind: TEXT) {
                    id systemId kind name nameLocal abbreviation abbreviationLocal description relatedSystemId
                    languages { iso name nameLocal script scriptCode scriptDirection }
                    attribution { copyright citation informationUrl }
                }
                sources(configurationId: "$id") {
                    id configurationId provider providerBibleId metadataId metadataVersion variant kind name nameLocal
                    abbreviation attribution { copyright citation informationUrl } allowedUses version created modified
                }
                audioTargets(configurationId: "$id", providerBibleId: "recording") { metadataId version variant name }
            } } }
        """.trimIndent())
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val data = assertNotNull(result.data).jsonObject.getValue("content").jsonObject.getValue("bibleProviders").jsonObject
        assertEquals("API_BIBLE", data.getValue("providers").jsonArray.single().jsonPrimitive.content)
        assertTrue(data.getValue("configurations").jsonArray.single().jsonObject.getValue("hasApiKey").jsonPrimitive.content.toBoolean())
        val entry = data.getValue("catalog").jsonArray.single().jsonObject
        assertEquals("eng", entry.getValue("languages").jsonArray.single().jsonObject.getValue("iso").jsonPrimitive.content)
        assertEquals("Copyright", entry.getValue("attribution").jsonObject.getValue("copyright").jsonPrimitive.content)
        val storedSource = data.getValue("sources").jsonArray.single().jsonObject
        assertEquals(sourceId.toString(), storedSource.getValue("id").jsonPrimitive.content)
        assertEquals(id.toString(), storedSource.getValue("configurationId").jsonPrimitive.content)
        assertEquals(metadataId.toString(), storedSource.getValue("metadataId").jsonPrimitive.content)
        assertEquals("7", storedSource.getValue("version").jsonPrimitive.content)
        assertEquals(setOf("DISPLAY", "AI_CONTEXT"), storedSource.getValue("allowedUses").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(metadataId.toString(), data.getValue("audioTargets").jsonArray.single().jsonObject.getValue("metadataId").jsonPrimitive.content)
    }

    @Test
    fun `configuration inputs preserve null keys and responses cannot select credentials`() = runTest {
        coEvery { providers.saveConfiguration(any()) } returns configuration
        for (key in listOf(emptyMap(), mapOf("apiKey" to null), mapOf("apiKey" to "new-secret"))) {
            val result = execute("""
                mutation Save(${'$'}input: BibleProviderConfigurationInput!) {
                    content { bibleProviders { saveConfiguration(input: ${'$'}input) { id name provider enabled hasApiKey } } }
                }
            """.trimIndent(), mapOf("input" to mapOf("id" to id.toString(), "name" to "Provider", "provider" to "API_BIBLE", "enabled" to true) + key))
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertFalse(result.data.toString().contains("new-secret"))
            coVerify { providers.saveConfiguration(BibleProviderConfigurationInput("Provider", "API_BIBLE", true, id, key["apiKey"])) }
        }
        coEvery { providers.getConfigurations() } returns listOf(configuration)
        val invalid = execute("{ content { bibleProviders { configurations { apiKey } } } }")
        assertTrue(invalid.errors.isNotEmpty())
        coVerify(exactly = 0) { providers.getConfigurations() }
    }

    @Test
    fun `mutations deserialize reviewed uses and select the exact recording target`() = runTest {
        val bibleInput = AddProviderBibleInput(id, "edition", "study", setOf(BibleContentUse.DISPLAY))
        val audioInput = AddProviderAudioInput(id, "recording", setOf(BibleContentUse.PLAYBACK))
        coEvery { providers.addBible(bibleInput) } returns bible
        coEvery { bibles.getVariants(metadataId, 3) } returns listOf(bible)
        coEvery { providers.addAudio(bible, audioInput) } returns BibleAudio(id, "Recording", "Local recording", "R", attribution)
        val result = execute("""
            mutation { content { bibleProviders {
                addBible(input: { configurationId: "$id", providerBibleId: "edition", variant: "study", allowedUses: [DISPLAY] }) {
                    metadataId version variant
                }
                addAudio(bibleId: "$metadataId", version: 3, variant: "study",
                    input: { configurationId: "$id", providerBibleId: "recording", allowedUses: [PLAYBACK] }) {
                    id name nameLocal abbreviation attribution { copyright citation informationUrl }
                }
            } } }
        """.trimIndent())
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue(result.data.toString().contains("Recording"))
        coVerify(exactly = 1) { providers.addBible(bibleInput); providers.addAudio(bible, audioInput) }
    }

    @Test
    fun `configuration testing refresh and removal use generated UUID Long and safe purge defaults`() = runTest {
        coEvery { providers.test(id) } just Runs
        coEvery { providers.refresh(id) } just Runs
        coEvery { providers.removeSource(id, 7) } just Runs
        coEvery { providers.removeConfiguration(id, any()) } just Runs
        val result = execute("""
            mutation { content { bibleProviders {
                test(configurationId: "$id")
                refresh(sourceId: "$id")
                removeSource(sourceId: "$id", expectedVersion: 7)
                removeConfiguration(configurationId: "$id")
                purge: removeConfiguration(configurationId: "$id", purgeSources: true)
            } } }
        """.trimIndent())
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        coVerify(exactly = 1) { providers.removeSource(id, 7); providers.removeConfiguration(id, false); providers.removeConfiguration(id, true) }
    }

    @Test
    fun `non-administrator queries and mutations cannot reach provider services`() = runTest {
        every { groups.verifyHasAdminGroup(authentication) } throws SecurityException("Unauthorized access")
        for (query in listOf(
            "{ content { bibleProviders { providers } } }",
            "{ content { bibleProviders { configurations { id } } } }",
            "{ content { bibleProviders { catalog(configurationId: \"$id\", kind: TEXT) { id } } } }",
            "mutation { content { bibleProviders { removeConfiguration(configurationId: \"$id\", purgeSources: true) } } }",
        )) {
            assertTrue(execute(query).errors.isNotEmpty())
        }
        coVerify { providers wasNot Called; bibles wasNot Called }
    }

    @Test
    fun `invalid reviewed use enums fail validation before registration`() = runTest {
        val result = execute("""
            mutation { content { bibleProviders {
                addBible(input: { configurationId: "$id", providerBibleId: "edition", variant: "study", allowedUses: [UNKNOWN] }) { metadataId }
            } } }
        """.trimIndent())
        assertTrue(result.errors.isNotEmpty())
        coVerify { providers wasNot Called }
    }

    @Test
    fun `missing authentication context cannot read administrator configuration`() = runTest {
        val result = graphql.execute(GraphQLRequest("{ content { bibleProviders { configurations { id } } } }"))
        assertTrue(result.errors.isNotEmpty())
        coVerify { providers wasNot Called }
    }
}
