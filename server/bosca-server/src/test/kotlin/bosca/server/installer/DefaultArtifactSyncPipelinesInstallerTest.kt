package bosca.server.installer

import bosca.artifacts.model.ArtifactTagPublished
import bosca.artifacts.model.ArtifactSyncTarget
import bosca.artifacts.pipeline.ArtifactSyncGetDestinations
import bosca.artifacts.pipeline.ArtifactSyncNode
import bosca.pipelines.builtin.ForEach
import bosca.pipelines.model.Pipeline
import bosca.pipelines.service.PipelineService
import bosca.serialization.UUID
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class DefaultArtifactSyncPipelinesInstallerTest {
    private fun setup(existing: List<Pipeline> = emptyList()): Pair<DefaultArtifactSyncPipelinesInstaller, MutableList<Pipeline>> {
        val captured = mutableListOf<Pipeline>()
        val service = mockk<PipelineService>()
        coEvery { service.getAll() } returns existing
        coEvery { service.graphAsJsonElement(capture(captured)) } returns JsonObject(emptyMap())
        coEvery { service.save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            captured.last().copy(id = UUID.random())
        }
        return DefaultArtifactSyncPipelinesInstaller(service) to captured
    }

    @Test fun `seeds a typed triggered graph and a durable per-destination body`() = runTest {
        val (installer, graphs) = setup()
        installer.install(mockk(relaxed = true), mockk(relaxed = true))
        assertEquals(2, graphs.size)
        val body = graphs.single { !it.triggered }
        assertEquals(ArtifactSyncTarget::class.qualifiedName, body.acceptedInputType)
        val triggered = graphs.single { it.triggered }
        assertIs<ArtifactSyncNode>(body.nodes[1])
        assertEquals("target", body.edges.first().targetPort)
        assertEquals(ArtifactTagPublished::class.qualifiedName, triggered.acceptedInputType)
        assertIs<ArtifactSyncGetDestinations>(triggered.nodes[1])
        val each = assertIs<ForEach>(triggered.nodes[2])
        assertNotEquals(UUID.NIL, each.pipelineId)
        assertTrue(each.continueOnError)
        assertEquals("artifact", triggered.edges.first().targetPort)
    }

    @Test fun `reinstall preserves edited graphs and the existing body reference`() = runTest {
        val body = Pipeline(id = UUID.random(), name = DefaultArtifactSyncPipelinesInstaller.BODY_NAME,
            acceptedInputType = "JSON")
        val (installer, graphs) = setup(listOf(body))
        installer.install(mockk(relaxed = true), mockk(relaxed = true))
        assertEquals(body.id, assertIs<ForEach>(graphs.single().nodes[2]).pipelineId)
        val (again, unchanged) = setup(listOf(body, graphs.single()))
        again.install(mockk(relaxed = true), mockk(relaxed = true))
        assertTrue(unchanged.isEmpty())
    }
}
