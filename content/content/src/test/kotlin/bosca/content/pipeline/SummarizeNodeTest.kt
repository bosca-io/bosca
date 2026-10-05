@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.pipeline

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.pipeline.SummarizeNode
import bosca.content.metadata.service.MetadataAIService
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**the summarize node reuses MetadataAIService and merges the result into a distinct attribute. */
class SummarizeNodeTest {

    private val metadataService = mockk<MetadataService>()
    private val metadataAIService = mockk<MetadataAIService>()

    private val json = Json
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<MetadataService> { metadataService }
        provides<MetadataAIService> { metadataAIService }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("" to value))

    /** A real typed Metadata input so the generated deserialize can bridge it through JSON. */
    private fun metaInput(id: Uuid = Uuid.random()): Pair<Metadata, PipelineValue> {
        val metadata = Metadata(
            id = id,
            name = "Doc",
            type = MetadataType.STANDARD,
            contentType = "text/plain",
            contentLength = null,
            languageTag = "en",
            workflowStateId = "draft",
        )
        return metadata to PipelineValue.of(metadata, Metadata.serializer())
    }

    @Test
    fun `summarizes and merges into the aiSummary attribute`() = runTest {
        val (metadata, input) = metaInput()
        coEvery { metadataAIService.description(metadata, null) } returns "A concise summary."
        val merged = slot<JsonElement>()
        coJustRun { metadataService.mergeAttributes(metadata, capture(merged)) }

        val out = SummarizeNode(id = "n1").executeForTest(context, inputs(input))

        assertSame(input, out)
        assertEquals("A concise summary.", merged.captured.jsonObject["aiSummary"]?.jsonPrimitive?.content)
        coVerify(exactly = 1) { metadataService.mergeAttributes(metadata, any<JsonElement>()) }
    }

    @Test
    fun `respects a configured attribute key`() = runTest {
        val (metadata, input) = metaInput()
        coEvery { metadataAIService.description(metadata, null) } returns "Dek text."
        val merged = slot<JsonElement>()
        coJustRun { metadataService.mergeAttributes(metadata, capture(merged)) }

        SummarizeNode(id = "n1", summaryAttribute = "dek").executeForTest(context, inputs(input))

        assertEquals("Dek text.", merged.captured.jsonObject["dek"]?.jsonPrimitive?.content)
    }

    @Test
    fun `dry run records the action and calls neither the model nor merge`() = runTest {
        val (_, input) = metaInput()
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = SummarizeNode(id = "n1").executeForTest(dry, inputs(input))

        assertSame(input, out)
        assertEquals(true, trace.actions.containsKey("n1"))
        coVerify(exactly = 0) { metadataAIService.description(any(), any()) }
        coVerify(exactly = 0) { metadataService.mergeAttributes(any(), any<JsonElement>()) }
    }

    @Test
    fun `dry run without a trace still calls neither the model nor merge`() = runTest {
        val (_, input) = metaInput()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)

        val out = SummarizeNode(id = "n1").executeForTest(dry, inputs(input))

        assertSame(input, out)
        coVerify(exactly = 0) { metadataAIService.description(any(), any()) }
        coVerify(exactly = 0) { metadataService.mergeAttributes(any(), any<JsonElement>()) }
    }

    @Test
    fun `passes through without merging when the summary is blank`() = runTest {
        val (metadata, input) = metaInput()
        coEvery { metadataAIService.description(metadata, null) } returns "   "

        val out = SummarizeNode(id = "n1").executeForTest(context, inputs(input))

        assertSame(input, out)
        coVerify(exactly = 0) { metadataService.mergeAttributes(any(), any<JsonElement>()) }
    }

    @Test
    fun `fails when the input is not a metadata`() = runTest {
        // The generated deserialize bridges through Metadata.serializer(), so a non-Metadata value
        // fails decoding.
        assertFailsWith<SerializationException> {
            SummarizeNode(id = "n1", name = "Summarize")
                .executeForTest(context, inputs(PipelineValue.ofJson(JsonPrimitive("not a metadata"))))
        }
    }

    @Test
    fun `fails with the generated required-input message when the input is missing`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            SummarizeNode(id = "n1").executeForTest(context, NodeInputs(emptyMap()))
        }
        assertEquals(true, e.message?.contains("required input 'in'"))
    }

    @Test
    fun `dry run tolerates a missing input and records an empty metadata id`() = runTest {
        // A dry run traces whatever is wired so far — a missing required input must not fail it.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        val out = SummarizeNode(id = "n1").executeForTest(dry, NodeInputs(emptyMap()))

        assertEquals(null, out)
        assertEquals("", trace.actions["n1"]?.jsonObject?.get("metadataId")?.jsonPrimitive?.content)
    }
}
