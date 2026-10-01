package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.federation.FederationAuth
import bosca.workops.model.federation.FederationConflict
import bosca.workops.model.federation.FederationConflictResolution
import bosca.workops.model.federation.FederationPeer
import bosca.workops.model.federation.FederationPeerKind
import bosca.workops.model.federation.FederationProjectFieldMask
import bosca.workops.model.federation.PrincipalMappingPolicy
import bosca.workops.model.federation.RemoteTaskSnapshot
import bosca.workops.repository.FederationConflictInsertParams
import bosca.workops.repository.FederationConflictRepository
import bosca.workops.repository.FederationFieldMaskRepository
import bosca.workops.repository.FederationPeerInsertParams
import bosca.workops.repository.FederationPeerRepository
import bosca.workops.repository.FederationPrincipalMappingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FederationServiceTest {

    private val json = Json

    @Test
    fun `peer service delegates reads sync and serializes every credential kind`() = runTest {
        val repository = mockk<FederationPeerRepository>()
        val service = FederationPeerServiceImpl(repository, json)
        val id = UUID.random()
        val peer = FederationPeer(
            id = id,
            name = "Partner",
            kind = FederationPeerKind.CUSTOM,
            baseUrl = "https://peer.invalid",
        )
        coEvery { repository.listAll() } returns listOf(peer)
        coEvery { repository.listEnabled() } returns listOf(peer)
        coEvery { repository.getById(id) } returns peer
        coEvery { repository.touchSync(id, "etag-2") } returns Unit

        assertEquals(listOf(peer), service.list())
        assertEquals(listOf(peer), service.listEnabled())
        assertSame(peer, service.getById(id))
        service.touchSync(id, "etag-2")

        val credentials = listOf<FederationAuth>(
            FederationAuth.BearerToken("token"),
            FederationAuth.OauthClient("client", "secret", "refresh"),
            FederationAuth.MutualTls("instance", "public-key"),
            FederationAuth.BasicAuth("user", "password"),
        )
        val captured = mutableListOf<FederationPeerInsertParams>()
        coEvery { repository.add(capture(captured)) } returns peer
        credentials.forEach { auth ->
            val input = CreateFederationPeerInput(
                name = "Partner",
                description = "Federated work",
                kind = FederationPeerKind.CUSTOM,
                baseUrl = "https://peer.invalid",
                auth = auth,
                principalMappingPolicy = PrincipalMappingPolicy.STRICT_EMAIL_MATCH,
                syncIntervalSeconds = 120,
                enabled = false,
            )
            assertSame(peer, service.create(input))
        }
        assertEquals(4, captured.size)
        captured.zip(credentials).forEach { (params, auth) ->
            assertEquals("Partner", params.name)
            assertEquals("Federated work", params.description)
            assertEquals("CUSTOM", params.kind)
            assertEquals("https://peer.invalid", params.baseUrl)
            assertEquals(auth, json.decodeFromString(FederationAuth.serializer(), params.authPayload))
            assertEquals("STRICT_EMAIL_MATCH", params.principalMappingPolicy)
            assertEquals(120, params.syncIntervalSeconds)
            assertEquals(false, params.enabled)
        }
    }

    @Test
    fun `field mask conflict and principal mapping services preserve repository contracts`() = runTest {
        val projectId = UUID.random()
        val peerId = UUID.random()
        val taskId = UUID.random()
        val maskRepository = mockk<FederationFieldMaskRepository>()
        val maskService = FederationFieldMaskServiceImpl(maskRepository)
        val mask = FederationProjectFieldMask(projectId, peerId, 17)
        coEvery { maskRepository.get(projectId, peerId) } returns mask
        coEvery { maskRepository.upsert(projectId, peerId, 17) } returns mask
        assertSame(mask, maskService.get(projectId, peerId))
        assertSame(mask, maskService.upsert(projectId, peerId, 17))

        val conflictRepository = mockk<FederationConflictRepository>()
        val conflictService = FederationConflictServiceImpl(conflictRepository, json)
        val conflict = FederationConflict(
            taskId = taskId,
            peerId = peerId,
            fieldKey = "summary",
            oldValue = JsonPrimitive("old"),
            newValue = JsonPrimitive("new"),
            triggeredBy = "pull",
        )
        coEvery { conflictRepository.listForTask(taskId, -2, 1) } returns listOf(conflict)
        coEvery { conflictRepository.listUnresolved(peerId, 500) } returns listOf(conflict)
        assertEquals(listOf(conflict), conflictService.listForTask(taskId, -2, 0))
        assertEquals(listOf(conflict), conflictService.listUnresolved(peerId, 999))

        val params = slot<FederationConflictInsertParams>()
        coEvery { conflictRepository.add(capture(params)) } returns conflict
        assertSame(
            conflict,
            conflictService.record(
                taskId, peerId, "summary", JsonPrimitive("old"), JsonPrimitive("new"), "pull",
            ),
        )
        assertEquals(taskId, params.captured.taskId)
        assertEquals(peerId, params.captured.peerId)
        assertEquals("summary", params.captured.fieldKey)
        assertEquals("\"old\"", params.captured.oldValue)
        assertEquals("\"new\"", params.captured.newValue)
        assertEquals("pull", params.captured.triggeredBy)
        coEvery { conflictRepository.resolve(conflict.id, "MERGED") } returns Unit
        conflictService.resolve(conflict.id, FederationConflictResolution.MERGED)

        val mappingRepository = mockk<FederationPrincipalMappingRepository>()
        val mappingService = FederationPrincipalMappingServiceImpl(mappingRepository)
        val profileId = UUID.random()
        coEvery { mappingRepository.propose(peerId, "remote", profileId, "user@example.com") } returns Unit
        coEvery { mappingRepository.accept(peerId, "remote", profileId) } returns Unit
        coEvery { mappingRepository.resolve(peerId, "remote") } returns profileId
        mappingService.propose(peerId, "remote", profileId, "user@example.com")
        mappingService.accept(peerId, "remote", profileId)
        assertEquals(profileId, mappingService.resolve(peerId, "remote"))
    }

    @Test
    fun `adapter registry returns registered implementations and an explicit pending adapter otherwise`() = runTest {
        val peer = FederationPeer(
            name = "Peer",
            kind = FederationPeerKind.LINEAR,
            baseUrl = "https://linear.invalid",
        )
        val snapshot = RemoteTaskSnapshot(
            remoteId = "1",
            remoteKey = "LIN-1",
            canonicalUrl = "https://linear.invalid/1",
            summary = "Remote task",
            descriptionMarkdown = null,
            statusName = null,
            priorityName = null,
            assigneeRemoteUserId = null,
        )
        val registered = mockk<FederationAdapter>()
        every { registered.kind } returns FederationPeerKind.LINEAR
        val registry = FederationAdapterRegistry(mapOf(FederationPeerKind.LINEAR to registered))
        assertSame(registered, registry.adapterFor(FederationPeerKind.LINEAR))
        assertSame(registered, registry.adapterFor(peer))

        val replacement = mockk<FederationAdapter>()
        every { replacement.kind } returns FederationPeerKind.LINEAR
        registry.register(replacement)
        assertSame(replacement, registry.adapterFor(peer))

        val pending = registry.adapterFor(FederationPeerKind.JIRA_CLOUD)
        assertTrue(pending is PendingFederationAdapter)
        assertEquals(FederationPeerKind.JIRA_CLOUD, pending.kind)
        assertEquals(emptyList(), pending.pull(peer, null))
        assertFailsWith<PendingPhaseImplementationException> {
            pending.push(peer, snapshot, setOf("summary"))
        }
    }
}
