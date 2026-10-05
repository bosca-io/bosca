@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.artifacts.pipeline

import bosca.artifacts.model.*
import bosca.artifacts.service.*
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.*
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

class ArtifactPublicationNodeTest {
    private val json = Json { serializersModule = SerializersModule {
        contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer())
        include(ArtifactsPipelineNodeSerializersProvider().module)
    } }
    private val artifacts = mockk<ArtifactRepositoryService>()
    private val publications = mockk<ArtifactPublicationService>()
    private val permissions = mockk<ArtifactPermissionEvaluator>()
    private val authentication = AuthenticationContext(null, null)
    private val namespace = ArtifactNamespace(UUID.random(), "builds", false)
    private val repository = ArtifactRepository(UUID.random(), namespace.id, "tool", "raw")
    private val version = ArtifactVersion(UUID.random(), repository.id, "1.0.0")
    private val destination = ArtifactPublicationDestination(UUID.random(), repository.id, "github", 123,
        "acme", "tool", "v", true, "github-token")
    private val completed = ArtifactCompleted(UUID.random(), version.id, listOf(UUID.random()), "1".repeat(40), null)
    private val target = ArtifactPublicationTarget(destination.id, version.id, completed.commitSha)
    private fun context(dryRun: Boolean = false, trace: DryRunTrace? = null, runId: UUID? = null) =
        PipelineContext(authentication, json, dryRun = dryRun, trace = trace, runId = runId)
    private fun eventInputs(event: ArtifactCompleted = completed) =
        NodeInputs(mapOf("artifact" to PipelineValue.of(event, ArtifactCompleted.serializer())))
    private fun targetInputs() = NodeInputs(mapOf("target" to PipelineValue.of(target, ArtifactPublicationTarget.serializer())))

    @BeforeTest fun setup() {
        provides<ArtifactRepositoryService> { artifacts }
        provides<ArtifactPublicationService> { publications }
        provides<ArtifactPermissionEvaluator> { permissions }
        coEvery { artifacts.findRepository("builds", "tool", ArtifactType.RAW) } returns repository
        coEvery { artifacts.findVersion(repository.id, "1.0.0") } returns version
        coEvery { artifacts.getVersion(version.id) } returns version
        coEvery { artifacts.getRepository(repository.id) } returns repository
        coEvery { artifacts.getNamespace(repository.namespaceId) } returns namespace
        coEvery { publications.destinations(repository.id) } returns listOf(destination)
        coEvery { permissions.verify(authentication, "raw", "builds", "tool", "1.0.0", ArtifactAction.PUSH) } returns Unit
    }

    @AfterTest fun cleanup() = ProviderRegistry.clear()

    @Test fun `get destinations returns enabled destinations without preparing or publishing anything`() = runBlocking {
        val other = destination.copy(id = UUID.random(), key = "mirror")
        coEvery { publications.destinations(repository.id) } returns listOf(destination, other, destination.copy(enabled = false))
        val result = assertIs<NodeResult.Output>(ArtifactPublicationGetDestinations("destinations").run(context(), eventInputs()))
        val targets = json.decodeFromJsonElement(ListSerializer(ArtifactPublicationTarget.serializer()), assertNotNull(result.value).encode(json))
        assertEquals(listOf(target, target.copy(destinationId = other.id)), targets)
        coVerify(exactly = 0) { artifacts.finalizeVersion(any()) }
        coVerify(exactly = 0) { publications.prepare(any(), any(), any(), any()) }
        coVerify(exactly = 0) { publications.publish(any()) }
    }

    @Test fun `push publishes only its selected destination and uses normal durable action suspension`() = runBlocking {
        val publication = ArtifactPublication(UUID.random(), destination.id, version.id, "v1.0.0", completed.commitSha, true, emptyList())
        coEvery { publications.prepare(destination.id, version.id, completed.commitSha, true) } returns publication
        coEvery { publications.publish(publication.id) } returns Unit
        val node = ArtifactPublicationNode("publish", prerelease = true)
        assertIs<NodeResult.Suspend>(node.run(context(runId = UUID.random()), targetInputs()))
        coVerify(exactly = 0) { publications.prepare(any(), any(), any(), any()) }
        val result = assertIs<NodeResult.Output>(node.run(context(), targetInputs()))
        assertEquals(publication.id.toString(), result.value?.encode(json)?.jsonPrimitive?.content)
        coVerify(exactly = 1) { publications.publish(publication.id) }
        coVerify(exactly = 0) { publications.destinations(any()) }
        for (original in listOf(node, ArtifactPublicationGetDestinations("destinations"))) {
            val restored = json.decodeFromString(PipelineNode.serializer(), json.encodeToString(PipelineNode.serializer(), original))
            assertEquals(original::class, restored::class)
        }
    }

    @Test fun `push independently checks permission even when its input bypasses destination lookup`() = runBlocking {
        coEvery { permissions.verify(authentication, "raw", "builds", "tool", "1.0.0", ArtifactAction.PUSH) } throws SecurityException("denied")
        assertFailsWith<SecurityException> { ArtifactPublicationNode("publish").run(context(), targetInputs()) }
        coVerify(exactly = 0) { publications.prepare(any(), any(), any(), any()) }
        coVerify(exactly = 0) { publications.publish(any()) }
    }

    @Test fun `get destinations checks permission before looking up destination configuration`() = runBlocking {
        coEvery { permissions.verify(authentication, "raw", "builds", "tool", "1.0.0", ArtifactAction.PUSH) } throws SecurityException("denied")
        assertFailsWith<SecurityException> { ArtifactPublicationGetDestinations("destinations").run(context(), eventInputs()) }
        coVerify(exactly = 0) { publications.destinations(any()) }
    }

    @Test fun `deleted or unconfigured artifacts produce an empty selection`() = runBlocking {
        val node = ArtifactPublicationGetDestinations("destinations")
        val deletedId = UUID.random()
        coEvery { artifacts.getVersion(deletedId) } returns null
        assertEquals(JsonArray(emptyList()), assertIs<NodeResult.Output>(node.run(context(), eventInputs(completed.copy(versionId = deletedId)))).value?.encode(json))
        coVerify(exactly = 0) { publications.destinations(any()) }
        coEvery { publications.destinations(repository.id) } returns listOf(destination.copy(enabled = false))
        assertEquals(JsonArray(emptyList()), assertIs<NodeResult.Output>(node.run(context(), eventInputs())).value?.encode(json))
        coVerify(exactly = 0) { artifacts.findVersion(any(), any()) }
        coVerify(exactly = 0) { publications.prepare(any(), any(), any(), any()) }
    }

    @Test fun `deleting a selected artifact cancels its push without preparing a publication`() = runBlocking {
        coEvery { artifacts.getVersion(version.id) } returns null
        val result = assertIs<NodeResult.Output>(ArtifactPublicationNode("publish").run(context(), targetInputs()))
        assertEquals("DELETED", result.value?.encode(json)?.jsonPrimitive?.content)
        coVerify(exactly = 0) { publications.prepare(any(), any(), any(), any()) }
        coVerify(exactly = 0) { publications.publish(any()) }
    }

    @Test fun `dry run requires no input and performs no service calls`() = runBlocking {
        val trace = DryRunTrace()
        val node = ArtifactPublicationNode("publish", prerelease = true)
        for (value in listOf(targetInputs(), NodeInputs(emptyMap()))) {
            val result = assertIs<NodeResult.Output>(node.run(context(dryRun = true, trace = trace), value))
            assertEquals("PREVIEW", result.value?.encode(json)?.jsonPrimitive?.content)
            assertTrue(trace.actions.getValue("publish").jsonObject.getValue("prerelease").jsonPrimitive.boolean)
        }
        val destinations = assertIs<NodeResult.Output>(ArtifactPublicationGetDestinations("destinations").run(context(dryRun = true), NodeInputs(emptyMap())))
        assertEquals(JsonArray(emptyList()), destinations.value?.encode(json))
        coVerify(exactly = 0) { publications.prepare(any(), any(), any(), any()) }
        coVerify(exactly = 0) { publications.publish(any()) }
        coVerify(exactly = 0) { artifacts.getVersion(any()) }
        coVerify(exactly = 0) { artifacts.findRepository(any(), any(), any()) }
    }
}
