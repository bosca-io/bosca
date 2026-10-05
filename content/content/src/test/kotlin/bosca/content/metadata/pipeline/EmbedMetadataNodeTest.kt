@file:OptIn(InternalDI::class, ExperimentalUuidApi::class)

package bosca.content.metadata.pipeline

import bosca.content.embedding.service.EmbeddingService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.PipelineValue
import bosca.search.IndexStorageSystem
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/** The Embed Metadata node extracts the item's body text, embeds it, and stores the vector. */
class EmbedMetadataNodeTest {

    private val embeddings = mockk<EmbeddingService>()
    private val json = Json { serializersModule = SerializersModule { contextual(UUIDSerializer()) } }
    private val context = PipelineContext(AuthenticationContext(null, null), json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<EmbeddingService> { embeddings }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun inputs(value: PipelineValue) = NodeInputs(mapOf("" to value))

    private fun metadata(id: Uuid) =
        PipelineValue.of(
            Metadata(
                id = id,
                name = "Doc",
                type = MetadataType.STANDARD,
                contentType = "text/plain",
                contentLength = null,
                languageTag = "en",
                workflowStateId = "draft",
            ),
            Metadata.serializer(),
        )

    @Test
    fun `embeds metadata and passes it through`() = runTest {
        val id = Uuid.random()
        coEvery { embeddings.embed(any<Metadata>()) } returns true

        val out = EmbedMetadataNode(id = "n1").run(context, inputs(metadata(id)))

        coVerify(exactly = 1) { embeddings.embed(match<Metadata> { it.id == id }) }
        // The Metadata is passed through unchanged so the node can chain.
        assertEquals(id, (out.result?.value as Metadata).id)
    }

    @Test
    fun `passes metadata through when the embedding service skips it`() = runTest {
        val id = Uuid.random()
        coEvery { embeddings.embed(any<Metadata>()) } returns false

        val out = EmbedMetadataNode(id = "n1").run(context, inputs(metadata(id)))

        assertEquals(id, (out.result?.value as Metadata).id)
    }

    @Test
    fun `dry run records the action and neither extracts, embeds, nor stores`() = runTest {
        val id = Uuid.random()
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        EmbedMetadataNode(id = "n1").run(dry, inputs(metadata(id)))

        assertEquals(true, trace.actions.containsKey("n1"))
        coVerify(exactly = 0) { embeddings.embed(any<Metadata>()) }
    }

    @Test
    fun `dry run without a trace still skips extract, embed, and store`() = runTest {
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true)

        EmbedMetadataNode(id = "n1").run(dry, inputs(metadata(Uuid.random())))

        coVerify(exactly = 0) { embeddings.embed(any<Metadata>()) }
    }

    @Test
    fun `requires a Metadata input`() = runTest {
        // The generated deserialize bridges through Metadata.serializer(), so a non-Metadata value
        // fails decoding.
        val notMetadata = PipelineValue.of(Uuid.random(), UUIDSerializer())
        assertFailsWith<SerializationException> {
            EmbedMetadataNode(id = "n1").run(context, inputs(notMetadata))
        }
    }

    @Test
    fun `a missing input fails with the generated required-input message`() = runTest {
        val e = assertFailsWith<IllegalStateException> {
            EmbedMetadataNode(id = "n1").run(context, NodeInputs(emptyMap()))
        }
        assertEquals(true, e.message?.contains("required input 'in'"))
    }

    @Test
    fun `dry run tolerates a missing input and records an empty metadata id`() = runTest {
        // A dry run traces whatever is wired so far — a missing required input must not fail it.
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), json, dryRun = true, trace = trace)

        EmbedMetadataNode(id = "n1").run(dry, NodeInputs(emptyMap()))

        assertEquals(true, trace.actions.containsKey("n1"))
        coVerify(exactly = 0) { embeddings.embed(any<Metadata>()) }
    }
}
