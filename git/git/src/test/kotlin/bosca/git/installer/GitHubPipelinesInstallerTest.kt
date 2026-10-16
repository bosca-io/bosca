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
            firstArg<String>().takeIf { it in saved }?.let { Pipeline(id = UUID.random(), name = it, acceptedInputType = "", tags = listOf("Git")) }
        }
        coEvery { pipelines.save(id = any(), name = any(), description = any(), acceptedInputType = any(), triggered = any(), version = any(), graph = any(), tags = any(), key = any(), schedule = any()) } answers {
            assertEquals(listOf("Git"), arg(7))
            saved[arg<String>(8)] = arg(6)
            Pipeline(id = UUID.random(), name = arg(1), acceptedInputType = arg(3))
        }
        val registry = GitHubPackageInstallerRegistry()
        val installation = registry.installation()
        val version = installation.versions.single()
        val installer = registry.installer(pipelines)
        assertEquals("1.5.0", version.version); assertEquals(version.version, installer.version)
        assertEquals(listOf("github-pipelines"), version.installerNames)
        installer.install(installation, version)
        assertEquals(setOf("github-import-refs", "github-export-refs", "github-reconcile-refs", "github-import-pull-requests", "github-export-pull-requests", "github-reconcile-pull-requests", "github-export-ci-status", "github-export-ci-evaluation", "github-reconcile-ci-statuses"), saved.keys)
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
                is bosca.git.pipeline.GitHubExportCiStatusNode -> "githubExportCiStatus"
                is bosca.git.pipeline.GitHubExportCiEvaluationNode -> "githubExportCiEvaluation"
                is bosca.git.pipeline.GitHubReconcileCiStatusesNode -> "githubReconcileCiStatuses"
                else -> "githubReconcilePullRequests"
            } }
            val declared = graph.nodes.filterIsInstance<HasDeclaredOutput>().associateBy { (it as PipelineNode).id }
            assertTrue(SlotConnectionValidator.validate(keys, graph.edges, descriptors, declared).isEmpty())
        }
        installer.install(installation, version)
        coVerify(exactly = 9) { pipelines.save(id = any(), name = any(), description = any(), acceptedInputType = any(), triggered = any(), version = any(), graph = any(), tags = listOf("Git"), key = any(), schedule = any()) }
        coVerify { pipelines.save(id = UUID.NIL, name = "GitHub: Import Refs", description = any(), acceptedInputType = "bosca.git.model.GitHubDelivery", triggered = true, version = 0, graph = any(), tags = listOf("Git"), key = "github-import-refs") }
        coVerify { pipelines.save(id = UUID.NIL, name = "GitHub: Reconcile Refs", description = any(), acceptedInputType = InputNode.JSON_TYPE, triggered = false, version = 0, graph = any(), tags = listOf("Git"), key = "github-reconcile-refs", schedule = "10 3 * * *") }
    }

    @Test fun `hourly recovery defaults migrate once while custom schedules stay unchanged`() = runBlocking {
        val pipelines = mockk<PipelineService>()
        val scheduled = listOf("github-reconcile-refs", "github-reconcile-pull-requests", "github-reconcile-ci-statuses")
        val stored = scheduled.associateWith { key ->
            Pipeline(id = UUID.random(), key = key, name = key, acceptedInputType = InputNode.JSON_TYPE,
                tags = listOf("Git", "Custom"), schedule = "0 * * * *", maxConcurrentRuns = 2, version = 4)
        }.toMutableMap()
        val graph = kotlinx.serialization.json.buildJsonObject { }
        coEvery { pipelines.getByKey(any()) } answers {
            stored[firstArg<String>()] ?: Pipeline(id = UUID.random(), name = "custom", acceptedInputType = "", tags = listOf("Git"))
        }
        coEvery { pipelines.graphAsJsonElement(any()) } returns graph
        coEvery { pipelines.save(id = any(), name = any(), description = any(), acceptedInputType = any(), triggered = any(),
            version = any(), graph = any(), tags = any(), key = any(), api = any(), public = any(), schedule = any(),
            maxConcurrentRuns = any(), maxRunsPerMinute = any()) } answers {
            val previous = stored.getValue(arg(8))
            assertEquals(previous.tags, arg(7))
            assertEquals(previous.maxConcurrentRuns, arg(12))
            previous.copy(schedule = arg(11), version = previous.version + 1).also { stored[previous.key.orEmpty()] = it }
        }
        val registry = GitHubPackageInstallerRegistry()
        val installation = registry.installation()
        val installer = registry.installer(pipelines)
        repeat(2) { installer.install(installation, installation.versions.single()) }
        assertEquals(listOf("10 3 * * *", "30 3 * * *", "50 3 * * *"), scheduled.map { stored.getValue(it).schedule })
        assertTrue(stored.values.all { it.version == 5L })
        for ((key, schedule) in scheduled.zip(listOf("15 */8 * * *", null, "0 4 * * 0"))) {
            stored[key] = stored.getValue(key).copy(schedule = schedule)
        }
        installer.install(installation, installation.versions.single())
        assertEquals(listOf("15 */8 * * *", null, "0 4 * * 0"), scheduled.map { stored.getValue(it).schedule })
        assertTrue(stored.values.all { it.version == 5L })
    }

    @Test fun `existing pipelines gain the Git tag once without losing operator settings`() = runBlocking {
        val pipelines = mockk<PipelineService>()
        val existing = Pipeline(
            id = UUID.random(), key = "github-import-refs", name = "Operator name", description = "Operator description",
            acceptedInputType = InputNode.JSON_TYPE, tags = listOf("Customized"), triggered = false,
            api = true, public = true, schedule = "15 * * * *", maxConcurrentRuns = 3, maxRunsPerMinute = 7, version = 4,
        )
        var stored = existing
        val graph = kotlinx.serialization.json.buildJsonObject { put("operatorGraph", kotlinx.serialization.json.JsonPrimitive(true)) }
        coEvery { pipelines.getByKey(any()) } answers {
            if (firstArg<String>() == existing.key) stored
            else existing.copy(key = firstArg(), tags = listOf("gIt", "Other"))
        }
        coEvery { pipelines.graphAsJsonElement(existing) } returns graph
        coEvery { pipelines.save(
            id = existing.id, name = existing.name, description = existing.description,
            acceptedInputType = existing.acceptedInputType, triggered = existing.triggered,
            version = existing.version, graph = graph, tags = listOf("Customized", "Git"),
            key = existing.key, api = existing.api, public = existing.public, schedule = existing.schedule,
            maxConcurrentRuns = existing.maxConcurrentRuns, maxRunsPerMinute = existing.maxRunsPerMinute,
        ) } answers {
            existing.copy(tags = arg(7), version = existing.version + 1).also { stored = it }
        }
        val registry = GitHubPackageInstallerRegistry()
        val installation = registry.installation()
        val installer = registry.installer(pipelines)
        repeat(2) { installer.install(installation, installation.versions.single()) }
        assertEquals(existing.copy(tags = listOf("Customized", "Git"), version = 5), stored)
        coVerify(exactly = 1) { pipelines.graphAsJsonElement(any()) }
        coVerify(exactly = 1) { pipelines.save(
            id = any(), name = any(), description = any(), acceptedInputType = any(), triggered = any(),
            version = any(), graph = any(), tags = any(), key = any(), api = any(), public = any(),
            schedule = any(), maxConcurrentRuns = any(), maxRunsPerMinute = any(),
        ) }
    }
}
