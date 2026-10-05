@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.pipeline

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.*
import bosca.git.service.GitHubSyncService
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.*
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

class GitHubNodesTest {
    private val json = Json { serializersModule = SerializersModule {
        contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer())
        include(GitPipelineNodeSerializersProvider().module)
    } }
    private val repositoryId = UUID.random()
    private val delivery = GitHubDelivery(UUID.random().toString(), repositoryId, "push", JsonObject(emptyMap()), "digest")
    private val event = RefUpdateEvent(repositoryId, "Repository", "refs/heads/main", "main", GitRefKind.BRANCH,
        GitRefUpdateAction.CREATED, afterSha = "1".repeat(40))
    private val service = mockk<GitHubSyncService>()
    private fun context(dryRun: Boolean = false, trace: DryRunTrace? = null, runId: UUID? = null) =
        PipelineContext(AuthenticationContext(null, null), json, dryRun = dryRun, trace = trace, runId = runId)
    private val pushInputs get() = NodeInputs(mapOf("delivery" to PipelineValue.of(delivery, GitHubDelivery.serializer())))
    private val refInputs get() = NodeInputs(mapOf("event" to PipelineValue.of(event, RefUpdateEvent.serializer())))
    private val pullRequest = PullRequestEvent(repositoryId, UUID.random(), 7, PullRequestEventAction.UPDATED,
        "Title", "feature", "main", UUID.random())
    private val pullRequestInputs get() = NodeInputs(mapOf("event" to PipelineValue.of(pullRequest, PullRequestEvent.serializer())))

    @BeforeTest fun setup() { provides<GitHubSyncService> { service } }
    @AfterTest fun cleanup() = ProviderRegistry.clear()

    @Test fun `typed nodes execute through the existing synchronization service and round trip through KSP registration`() = runBlocking {
        coEvery { service.synchronizePush(delivery) } returns GitHubSyncResult.APPLIED
        coEvery { service.synchronizeRef(event) } returns GitHubSyncResult.CONFLICT
        coEvery { service.reconcileRefs() } returns listOf(GitHubRefState(repositoryId, "refs/heads/main"))
        coEvery { service.synchronizePullRequest(delivery) } returns GitHubSyncResult.APPLIED
        coEvery { service.synchronizePullRequest(pullRequest) } returns GitHubSyncResult.UNCHANGED
        coEvery { service.reconcilePullRequests() } returns listOf(GitHubPullRequestState(repositoryId = repositoryId))
        val push = GitHubPushNode("push", "Import", "Original delivery", NodePosition(10.0, 20.0))
        val ref = GitHubRefNode("ref", "Export", "Original ref", NodePosition(30.0, 40.0))
        val imported = push.run(context(), pushInputs) as NodeResult.Output
        val exported = ref.run(context(), refInputs) as NodeResult.Output
        assertEquals("APPLIED", imported.value?.encode(json)?.jsonPrimitive?.content)
        assertEquals("CONFLICT", exported.value?.encode(json)?.jsonPrimitive?.content)
        val reconcile = GitHubReconcileRefsNode("reconcile", "Reconcile", "Missed refs", NodePosition(50.0, 60.0))
        val reconciled = reconcile.run(context(), NodeInputs(mapOf("request" to PipelineValue.ofJson(JsonObject(emptyMap()))))) as NodeResult.Output
        assertEquals("1", reconciled.value?.encode(json)?.jsonPrimitive?.content)
        val importPr = GitHubImportPullRequestNode("importPr", "Import", "Current PR", NodePosition(70.0, 80.0))
        val exportPr = GitHubExportPullRequestNode("exportPr", "Export", "Current PR", NodePosition(90.0, 100.0))
        val reconcilePr = GitHubReconcilePullRequestsNode("reconcilePr", "Reconcile", "Missed PRs", NodePosition(110.0, 120.0))
        assertEquals("APPLIED", ((importPr.run(context(), pushInputs) as NodeResult.Output).value?.encode(json))?.jsonPrimitive?.content)
        assertEquals("UNCHANGED", ((exportPr.run(context(), pullRequestInputs) as NodeResult.Output).value?.encode(json))?.jsonPrimitive?.content)
        assertEquals("1", ((reconcilePr.run(context(), NodeInputs(mapOf("request" to PipelineValue.ofJson(JsonNull)))) as NodeResult.Output).value?.encode(json))?.jsonPrimitive?.content)
        for (node in listOf(push, ref, reconcile, importPr, exportPr, reconcilePr)) {
            val decoded = json.decodeFromString(PipelineNode.serializer(), json.encodeToString(PipelineNode.serializer(), node))
            assertEquals(node.id, decoded.id); assertEquals(node.name, decoded.name)
            assertEquals(node.description, decoded.description); assertEquals(node.position, decoded.position)
            assertTrue(decoded.willSuspend)
        }
    }

    @Test fun `dry run traces effects without calling services and tolerates unwired inputs`() = runBlocking {
        for ((node, inputs) in listOf(GitHubPushNode("push") to pushInputs, GitHubRefNode("ref") to refInputs,
            GitHubReconcileRefsNode("reconcile") to NodeInputs(mapOf("request" to PipelineValue.ofJson(JsonObject(emptyMap())))),
            GitHubImportPullRequestNode("importPr") to pushInputs, GitHubExportPullRequestNode("exportPr") to pullRequestInputs,
            GitHubReconcilePullRequestsNode("reconcilePr") to NodeInputs(mapOf("request" to PipelineValue.ofJson(JsonObject(emptyMap())))))) {
            val trace = DryRunTrace()
            for (value in listOf(inputs, NodeInputs(emptyMap()))) {
                val output = node.run(context(dryRun = true, trace = trace), value) as NodeResult.Output
                assertEquals("PREVIEW", output.value?.encode(json)?.jsonPrimitive?.content)
                assertTrue(node.id in trace.actions)
            }
            assertFailsWith<IllegalStateException> { node.run(context(), NodeInputs(emptyMap())) }
            assertIs<NodeResult.Suspend>(node.run(context(runId = UUID.random()), inputs))
        }
        coVerify(exactly = 0) { service.synchronizePush(any()) }
        coVerify(exactly = 0) { service.synchronizeRef(any()) }
        coVerify(exactly = 0) { service.reconcileRefs(any()) }
        coVerify(exactly = 0) { service.synchronizePullRequest(any<GitHubDelivery>()) }
        coVerify(exactly = 0) { service.synchronizePullRequest(any<PullRequestEvent>()) }
        coVerify(exactly = 0) { service.reconcilePullRequests(any()) }
    }
}
