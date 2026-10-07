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

class ArtifactSyncNodeTest {
    private val json = Json { serializersModule = SerializersModule {
        contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer())
        include(ArtifactsPipelineNodeSerializersProvider().module)
    } }
    private val artifacts = mockk<ArtifactRepositoryService>()
    private val syncing = mockk<ArtifactSyncService>()
    private val permissions = mockk<ArtifactPermissionEvaluator>()
    private val authentication = AuthenticationContext(null, null)
    private val namespace = ArtifactNamespace(UUID.random(), "images", false)
    private val repository = ArtifactRepository(UUID.random(), namespace.id, "server", "docker")
    private val digest = "sha256:" + "a".repeat(64)
    private val version = ArtifactVersion(UUID.random(), repository.id, digest)
    private val destination = ArtifactSyncDestination(UUID.random(), repository.id, "ghcr", "acme/server", "acme", "token", true)
    private val event = ArtifactTagPublished(UUID.random(), repository.id, version.id, "latest", digest)
    private val target = ArtifactSyncTarget(destination.id, version.id, event.tagName, digest)
    private fun context(dryRun: Boolean = false, runId: UUID? = null, trace: DryRunTrace? = null) =
        PipelineContext(authentication, json, dryRun = dryRun, runId = runId, trace = trace)
    private fun eventInputs() = NodeInputs(mapOf("artifact" to PipelineValue.of(event, ArtifactTagPublished.serializer())))
    private fun targetInputs() = NodeInputs(mapOf("target" to PipelineValue.of(target, ArtifactSyncTarget.serializer())))

    @BeforeTest fun setup() {
        provides<ArtifactRepositoryService> { artifacts }
        provides<ArtifactSyncService> { syncing }
        provides<ArtifactPermissionEvaluator> { permissions }
        coEvery { artifacts.getVersion(version.id) } returns version
        coEvery { artifacts.getRepository(repository.id) } returns repository
        coEvery { artifacts.getNamespace(namespace.id) } returns namespace
        coEvery { artifacts.findTag(repository.id, "latest") } returns ArtifactTag(UUID.random(), repository.id, "latest", digest)
        coEvery { syncing.destinations(repository.id) } returns listOf(destination)
        coEvery { permissions.verify(authentication, "docker", "images", "server", "latest", ArtifactAction.PUSH) } returns Unit
    }
    @AfterTest fun cleanup() = ProviderRegistry.clear()

    @Test fun `destination lookup selects enabled targets without preparing or copying`() = runBlocking {
        coEvery { syncing.destinations(repository.id) } returns listOf(destination, destination.copy(id = UUID.random(), enabled = false))
        val result = assertIs<NodeResult.Output>(ArtifactSyncGetDestinations("destinations").run(context(), eventInputs()))
        assertEquals(listOf(target), json.decodeFromJsonElement(ListSerializer(ArtifactSyncTarget.serializer()), assertNotNull(result.value).encode(json)))
        coVerify(exactly = 0) { syncing.prepare(any()) }
        coVerify(exactly = 0) { syncing.sync(any()) }
    }

    @Test fun `sync action suspends durably and copies only the selected destination`() = runBlocking {
        val sync = ArtifactSync(UUID.random(), destination.id, version.id, "latest", digest)
        coEvery { syncing.prepare(target) } returns sync
        coEvery { syncing.sync(sync.id) } returns Unit
        val node = ArtifactSyncNode("sync")
        assertIs<NodeResult.Suspend>(node.run(context(runId = UUID.random()), targetInputs()))
        coVerify(exactly = 0) { syncing.prepare(any()) }
        val output = assertIs<NodeResult.Output>(node.run(context(), targetInputs()))
        assertEquals(sync.id.toString(), output.value?.encode(json)?.jsonPrimitive?.content)
        coVerify(exactly = 1) { syncing.sync(sync.id) }
        coVerify(exactly = 0) { syncing.destinations(any()) }
        for (original in listOf(node, ArtifactSyncGetDestinations("destinations"))) {
            assertEquals(original::class, json.decodeFromString(PipelineNode.serializer(),
                json.encodeToString(PipelineNode.serializer(), original))::class)
        }
    }

    @Test fun `both nodes independently verify push permission before using sync configuration`() = runBlocking {
        coEvery { permissions.verify(authentication, "docker", "images", "server", "latest", ArtifactAction.PUSH) } throws SecurityException("denied")
        assertFailsWith<SecurityException> { ArtifactSyncGetDestinations("destinations").run(context(), eventInputs()) }
        assertFailsWith<SecurityException> { ArtifactSyncNode("sync").run(context(), targetInputs()) }
        coVerify(exactly = 0) { syncing.destinations(any()) }
        coVerify(exactly = 0) { syncing.prepare(any()) }
    }

    @Test fun `obsolete tag events and deleted sources have no destinations`() = runBlocking {
        coEvery { artifacts.findTag(repository.id, "latest") } returns ArtifactTag(UUID.random(), repository.id, "latest", "sha256:" + "b".repeat(64))
        val node = ArtifactSyncGetDestinations("destinations")
        assertEquals(JsonArray(emptyList()), assertIs<NodeResult.Output>(node.run(context(), eventInputs())).value?.encode(json))
        coEvery { artifacts.getVersion(version.id) } returns null
        assertEquals(JsonArray(emptyList()), assertIs<NodeResult.Output>(node.run(context(), eventInputs())).value?.encode(json))
        assertEquals("SKIPPED", assertIs<NodeResult.Output>(ArtifactSyncNode("sync").run(context(), targetInputs())).value?.encode(json)?.jsonPrimitive?.content)
        coVerify(exactly = 0) { syncing.destinations(any()) }
        coVerify(exactly = 0) { syncing.prepare(any()) }
    }

    @Test fun `disabled or moved targets are skipped after selection`() = runBlocking {
        coEvery { syncing.prepare(target) } returns null
        assertEquals("SKIPPED", assertIs<NodeResult.Output>(ArtifactSyncNode("sync").run(context(), targetInputs())).value?.encode(json)?.jsonPrimitive?.content)
        coVerify(exactly = 0) { syncing.sync(any()) }
    }

    @Test fun `dry runs perform no service calls even without inputs`() = runBlocking {
        val trace = DryRunTrace()
        val output = assertIs<NodeResult.Output>(ArtifactSyncNode("sync").run(context(dryRun = true, trace = trace), NodeInputs(emptyMap())))
        assertEquals("PREVIEW", output.value?.encode(json)?.jsonPrimitive?.content)
        assertEquals("artifactSync", trace.actions.getValue("sync").jsonObject.getValue("action").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), assertIs<NodeResult.Output>(ArtifactSyncGetDestinations("destinations").run(context(dryRun = true), NodeInputs(emptyMap()))).value?.encode(json))
        coVerify(exactly = 0) { artifacts.getVersion(any()) }
        coVerify(exactly = 0) { syncing.prepare(any()) }
        coVerify(exactly = 0) { syncing.sync(any()) }
    }
}
