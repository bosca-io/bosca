@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.recommendations.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.recommendations.service.ClassificationService
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**the node resolves the inbound metadata-ready event and classifies the item. */
class ClassifyNodeTest {

    private val service = mockk<ClassificationService>()
    private val json = Json {
        serializersModule = SerializersModule {
            contextual(UUIDSerializer())
            contextual(OffsetDateTimeSerializer())
        }
    }
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ClassificationService> { service }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("" to value))

    // A real Metadata: the node decodes the slot via Metadata.serializer(), which a mock can't survive.
    private fun metadata(id: Uuid) = PipelineValue.of(
        Metadata(
            id = id,
            name = "item",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "published",
        ),
        Metadata.serializer(),
    )

    @Test
    fun `classifies the inbound metadata and emits the classification result`() = runTest {
        val id = Uuid.random()
        val categoryId = Uuid.random()
        coEvery { service.classify(id) } returns listOf(categoryId)

        val out = ClassifyNode(id = "n1").run(context, inputs(metadata(id)))

        val result = out.result?.value as ClassifyResult
        assertEquals(id, result.id)
        assertEquals(listOf(categoryId), result.categoryIds)
        coVerify(exactly = 1) { service.classify(id) }
    }

    @Test
    fun `dry run records the action and does not call the service`() = runTest {
        val id = Uuid.random()
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        ClassifyNode(id = "n1").run(dry, inputs(metadata(id)))

        assertEquals(true, trace.actions.containsKey("n1"))
        coVerify(exactly = 0) { service.classify(any()) }
    }

    @Test
    fun `dry run without a trace passes the input through`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = null)
        val out = ClassifyNode(id = "n1").run(dry, inputs(metadata(Uuid.random())))
        assertEquals(NodeResult.Output::class, out::class)
    }

    @Test
    fun `execute requires a metadata input`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            ClassifyNode(id = "n1").run(context, NodeInputs(emptyMap()))
        }
        assertEquals(true, e.message?.contains("required input 'in'"))
    }

    @Test
    fun `execute rejects a non-metadata input`() = runTest {
        val nonMetadata = PipelineValue.of("nope", String.serializer())
        assertFailsWith<SerializationException> {
            ClassifyNode(id = "named").run(context, inputs(nonMetadata))
        }
    }

    @Test
    fun `dry run tolerates a missing input, tracing an empty metadata id`() = runTest {
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        ClassifyNode(id = "n1").run(dry, NodeInputs(emptyMap()))

        val recorded = trace.actions["n1"] as JsonObject
        assertEquals("", recorded["metadataId"]?.jsonPrimitive?.content)
        coVerify(exactly = 0) { service.classify(any()) }
    }

    @Test
    fun `construction defaults, full construction and serialization round-trip`() {
        assertEquals("", ClassifyNode(id = "id-1").name)
        val full = ClassifyNode(id = "id-2", name = "Classifier", description = "desc", position = NodePosition())
        assertEquals("Classifier", full.name)
        val encoded = json.encodeToString(ClassifyNode.serializer(), full)
        assertEquals("id-2", json.decodeFromString(ClassifyNode.serializer(), encoded).id)
        // Minimal object → the "field absent → default" branch of each optional property.
        assertEquals("id-3", json.decodeFromString(ClassifyNode.serializer(), """{"id":"id-3"}""").id)
        // Encoding a defaults-only node exercises the serializer's "value equals default → skip" branches.
        val reEncoded = json.encodeToString(ClassifyNode.serializer(), ClassifyNode(id = "id-4"))
        assertEquals("id-4", json.decodeFromString(ClassifyNode.serializer(), reEncoded).id)
        // encodeDefaults writes the optional fields too → the serializer's "encode default" branch.
        val withDefaults = Json { serializersModule = SerializersModule { contextual(bosca.serialization.UUIDSerializer()) }; encodeDefaults = true }
        assertEquals("id-5", withDefaults.decodeFromString(ClassifyNode.serializer(), withDefaults.encodeToString(ClassifyNode.serializer(), ClassifyNode("id-5"))).id)
        // The strict decoder (json has no ignoreUnknownKeys) rejects an unknown key → unknown-field branch.
        assertFailsWith<Exception> { json.decodeFromString(ClassifyNode.serializer(), """{"id":"x","unexpected":1}""") }
    }

    @Test
    fun `ClassifyResult equality and serialization`() {
        val id = Uuid.random()
        val cat = Uuid.random()
        val a = ClassifyResult(id, listOf(cat))
        assertEquals(a, ClassifyResult(id, listOf(cat)))
        assertNotEquals(a, ClassifyResult(Uuid.random(), listOf(cat)))
        assertEquals(a.hashCode(), ClassifyResult(id, listOf(cat)).hashCode())
        val encoded = json.encodeToString(ClassifyResult.serializer(), a)
        assertEquals(a, json.decodeFromString(ClassifyResult.serializer(), encoded))
    }
}
