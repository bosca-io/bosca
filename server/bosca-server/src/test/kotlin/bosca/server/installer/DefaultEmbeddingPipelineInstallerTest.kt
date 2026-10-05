package bosca.server.installer

import bosca.content.metadata.events.MetadataSetReady
import bosca.content.metadata.pipeline.EmbedMetadataNode
import bosca.content.metadata.pipeline.MetadataEventToMetadataNode
import bosca.pipelines.builtin.GetIdNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.InputNode
import bosca.pipelines.service.PipelineService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultEmbeddingPipelineInstallerTest {

    private fun installerWith(existing: List<String>): Triple<DefaultEmbeddingPipelineInstaller, MutableList<Pipeline>, PipelineService> {
        val captured = mutableListOf<Pipeline>()
        val ps = mockk<PipelineService>(relaxed = true)
        coEvery { ps.getAll() } returns existing.map { name -> mockk<Pipeline> { every { this@mockk.name } returns name } }
        // The installer encodes its typed graph here — capture it; the encoded JSON is irrelevant to the test.
        coEvery { ps.graphAsJsonElement(capture(captured)) } returns JsonObject(emptyMap())
        return Triple(DefaultEmbeddingPipelineInstaller(ps), captured, ps)
    }

    private suspend fun DefaultEmbeddingPipelineInstaller.run() =
        install(mockk(relaxed = true), mockk(relaxed = true))

    @Test
    fun `seeds one triggered pipeline that chains Input to Get Id to Get Metadata to Embed`() = runTest {
        val (installer, captured, ps) = installerWith(emptyList())
        installer.run()

        assertEquals(1, captured.size)
        val p = captured.single()
        assertTrue(p.triggered, "the embedding pipeline must be triggered")
        // Fires when content becomes ready, and the Input node declares that same type.
        assertEquals(MetadataSetReady::class.qualifiedName, p.acceptedInputType)
        assertEquals(p.acceptedInputType, (p.nodes.first { it is InputNode } as InputNode).acceptedType)
        // Node chain: Input → Get Id → Get Metadata → Embed Metadata.
        assertTrue(p.nodes[0] is InputNode)
        assertTrue(p.nodes[1] is GetIdNode)
        assertTrue(p.nodes[2] is MetadataEventToMetadataNode)
        assertTrue(p.nodes[3] is EmbedMetadataNode)
        // Linear edges connect the chain end to end.
        assertEquals(listOf("input", "getId", "get"), p.edges.map { it.source })
        assertEquals(listOf("getId", "get", "embed"), p.edges.map { it.target })
        coVerify(exactly = 1) {
            ps.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `is idempotent - skips when the embedding pipeline already exists`() = runTest {
        val (installer, captured, ps) = installerWith(listOf("Content Embeddings"))
        installer.run()

        assertTrue(captured.isEmpty(), "the already-present pipeline is not recreated")
        coVerify(exactly = 0) {
            ps.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
