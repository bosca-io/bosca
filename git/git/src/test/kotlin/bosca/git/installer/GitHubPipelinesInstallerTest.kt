package bosca.git.installer

import bosca.git.pipeline.GitHubPushNode
import bosca.git.pipeline.GitHubRefNode
import bosca.git.pipeline.GitHubReconcileRefsNode
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineGraph
import bosca.pipelines.node.*
import bosca.pipelines.service.PipelineService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlin.test.*

class GitHubPipelinesInstallerTest {
    @Test fun `package seeds valid directional graphs once and preserves edited pipelines`() = runBlocking {
        val pipelines = mockk<PipelineService>()
        val saved = linkedMapOf<String, JsonElement>()
        coEvery { pipelines.getByKey(any()) } answers {
            firstArg<String>().takeIf { it in saved }?.let { Pipeline(id = UUID.random(), name = it, acceptedInputType = "") }
        }
        coEvery { pipelines.save(id = any(), name = any(), description = any(), acceptedInputType = any(), triggered = any(), version = any(), graph = any(), key = any(), schedule = any()) } answers {
            saved[arg<String>(8)] = arg(6)
            Pipeline(id = UUID.random(), name = arg(1), acceptedInputType = arg(3))
        }
        val registry = GitHubPackageInstallerRegistry()
        val installation = registry.installation()
        val version = installation.versions.single()
        val installer = registry.installer(pipelines)
        assertEquals("1.1.0", version.version); assertEquals(version.version, installer.version)
        assertEquals(listOf("github-pipelines"), version.installerNames)
        installer.install(installation, version)
        assertEquals(setOf("github-import-refs", "github-export-refs", "github-reconcile-refs", "github-import-pull-requests", "github-export-pull-requests", "github-reconcile-pull-requests"), saved.keys)
        val json = Json { serializersModule = SerializersModule {
            include(GitPipelineNodeSerializersProvider().module)
            polymorphic(PipelineNode::class) {
                subclass(InputNode::class, InputNode.serializer()); subclass(OutputNode::class, OutputNode.serializer())
            }
        } }
        val graphs = saved.values.map { json.decodeFromJsonElement(PipelineGraph.serializer(), it) }
        assertIs<GitHubPushNode>(graphs[0].nodes[1]); assertIs<GitHubRefNode>(graphs[1].nodes[1])
        assertIs<GitHubReconcileRefsNode>(graphs[2].nodes[1])
        assertEquals("bosca.git.model.GitHubDelivery", (graphs[0].nodes[0] as InputNode).acceptedType)
        assertEquals("bosca.git.model.RefUpdateEvent", (graphs[1].nodes[0] as InputNode).acceptedType)
        for (graph in graphs) {
            assertEquals(3, graph.nodes.map { it.position }.toSet().size)
            assertEquals(2, graph.edges.size)
            val descriptors = (CorePipelinesPipelineNodeSerializersProvider().descriptors + GitPipelineNodeSerializersProvider().descriptors)
                .associateBy { it.key }
            val keys = graph.nodes.associate { node -> node.id to when (node) {
                is InputNode -> "input"
                is OutputNode -> "output"
                is GitHubPushNode -> "githubPush"
                is GitHubRefNode -> "githubRef"
                is GitHubReconcileRefsNode -> "githubReconcileRefs"
                is bosca.git.pipeline.GitHubImportPullRequestNode -> "githubImportPullRequest"
                is bosca.git.pipeline.GitHubExportPullRequestNode -> "githubExportPullRequest"
                else -> "githubReconcilePullRequests"
            } }
            val declared = graph.nodes.filterIsInstance<HasDeclaredOutput>().associateBy { (it as PipelineNode).id }
            assertTrue(SlotConnectionValidator.validate(keys, graph.edges, descriptors, declared).isEmpty())
        }
        installer.install(installation, version)
        coVerify(exactly = 6) { pipelines.save(id = any(), name = any(), description = any(), acceptedInputType = any(), triggered = any(), version = any(), graph = any(), key = any(), schedule = any()) }
        coVerify { pipelines.save(id = UUID.NIL, name = "GitHub: Import Refs", description = any(), acceptedInputType = "bosca.git.model.GitHubDelivery", triggered = true, version = 0, graph = any(), key = "github-import-refs") }
        coVerify { pipelines.save(id = UUID.NIL, name = "GitHub: Reconcile Refs", description = any(), acceptedInputType = InputNode.JSON_TYPE, triggered = false, version = 0, graph = any(), key = "github-reconcile-refs", schedule = "0 * * * *") }
    }
}
