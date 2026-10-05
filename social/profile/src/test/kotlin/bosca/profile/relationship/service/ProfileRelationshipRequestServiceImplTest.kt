package bosca.profile.relationship.service

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.Event
import bosca.pipelines.PipelineEventDispatcher
import bosca.profile.relationship.events.ProfileRelationshipRequestApproved
import bosca.profile.relationship.model.ProfileRelationship
import bosca.profile.relationship.model.ProfileRelationshipRequest
import bosca.profile.relationship.model.ProfileRelationshipRequestStatus
import bosca.profile.relationship.repository.ProfileRelationshipRequestRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(InternalDI::class)
class ProfileRelationshipRequestServiceImplTest {

    private val repository = mockk<ProfileRelationshipRequestRepository>()
    private val relationshipService = mockk<ProfileRelationshipService>()
    private val service = ProfileRelationshipRequestServiceImpl(repository, relationshipService)
    private val pipelineEvents = mutableListOf<Event>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        pipelineEvents.clear()
        provides<Json> { Json }
        provides<PipelineEventDispatcher> {
            object : PipelineEventDispatcher {
                override suspend fun <T : Event> dispatch(
                    eventName: String,
                    event: T,
                    serializer: KSerializer<T>,
                ) {
                    pipelineEvents += event
                }
            }
        }
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction<ProfileRelationshipRequest>(any()) } coAnswers {
            firstArg<suspend () -> ProfileRelationshipRequest>().invoke()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        ProviderRegistry.clear()
    }

    @Test
    fun `getById delegates to the repository`() = runTest {
        val request = relationshipRequest()
        coEvery { repository.getById(request.id) } returns request

        assertEquals(request, service.getById(request.id))
    }

    @Test
    fun `incoming requests use the untyped repository query`() = runTest {
        val targetId = UUID.random()
        val requests = listOf(relationshipRequest(targetProfileId = targetId))
        coEvery { repository.getIncoming(targetId, 4, 12) } returns requests

        assertEquals(requests, service.getIncoming(targetId, null, offset = 4, limit = 12))
        coVerify(exactly = 1) { repository.getIncoming(targetId, 4, 12) }
    }

    @Test
    fun `incoming requests use the typed repository query`() = runTest {
        val targetId = UUID.random()
        val requests = listOf(relationshipRequest(targetProfileId = targetId))
        coEvery { repository.getIncomingByType(targetId, "friend", 2, 8) } returns requests

        assertEquals(requests, service.getIncoming(targetId, "friend", offset = 2, limit = 8))
    }

    @Test
    fun `outgoing requests use the untyped repository query`() = runTest {
        val requesterId = UUID.random()
        val requests = listOf(relationshipRequest(requesterProfileId = requesterId))
        coEvery { repository.getOutgoing(requesterId, 3, 9) } returns requests

        assertEquals(requests, service.getOutgoing(requesterId, null, offset = 3, limit = 9))
    }

    @Test
    fun `outgoing requests use the typed repository query`() = runTest {
        val requesterId = UUID.random()
        val requests = listOf(relationshipRequest(requesterProfileId = requesterId))
        coEvery { repository.getOutgoingByType(requesterId, "friend", 1, 7) } returns requests

        assertEquals(requests, service.getOutgoing(requesterId, "friend", offset = 1, limit = 7))
    }

    @Test
    fun `request rejects the same requester and target`() = runTest {
        val profileId = UUID.random()

        val exception = assertFailsWith<IllegalArgumentException> {
            service.request(profileId, profileId, "friend")
        }

        assertEquals("a profile cannot request a relationship with itself", exception.message)
    }

    @Test
    fun `request rejects an existing relationship`() = runTest {
        val requesterId = UUID.random()
        val targetId = UUID.random()
        coEvery { relationshipService.getRelationship(requesterId, targetId, "friend") } returns
            ProfileRelationship(requesterId, targetId, "friend")

        val exception = assertFailsWith<IllegalStateException> {
            service.request(requesterId, targetId, "friend")
        }

        assertEquals("relationship already exists", exception.message)
    }

    @Test
    fun `request creates a pending request when no relationship exists`() = runTest {
        val requesterId = UUID.random()
        val targetId = UUID.random()
        val attributes = buildJsonObject { put("message", "hello") }
        val request = relationshipRequest(requesterId, targetId).copy(attributes = attributes)
        coEvery { relationshipService.getRelationship(requesterId, targetId, "friend") } returns null
        coEvery { repository.add(requesterId, targetId, "friend", attributes) } returns request

        assertEquals(request, service.request(requesterId, targetId, "friend", attributes))
        coVerify(exactly = 1) { bosca.db.transaction<ProfileRelationshipRequest>(any()) }
    }

    @Test
    fun `approve transitions the request and creates its actual relationship`() = runTest {
        val pending = relationshipRequest(attributes = buildJsonObject { put("source", "request") })
        val approved = pending.copy(status = ProfileRelationshipRequestStatus.APPROVED, version = 1)
        coEvery { repository.getById(pending.id) } returns pending
        coEvery {
            repository.transition(pending.id, pending.version, ProfileRelationshipRequestStatus.APPROVED)
        } returns approved
        coEvery {
            relationshipService.addRelationship(
                pending.requesterProfileId,
                pending.targetProfileId,
                pending.type,
                pending.attributes,
            )
        } just Runs

        assertEquals(approved, service.approve(pending.id))
        coVerify(exactly = 1) {
            relationshipService.addRelationship(
                pending.requesterProfileId,
                pending.targetProfileId,
                pending.type,
                pending.attributes,
            )
        }
        assertEquals(
            listOf(pending.id),
            pipelineEvents.filterIsInstance<ProfileRelationshipRequestApproved>().map { it.requestId },
        )
    }

    @Test
    fun `decline transitions a pending request without creating a relationship`() = runTest {
        val pending = relationshipRequest()
        val declined = pending.copy(status = ProfileRelationshipRequestStatus.DECLINED, version = 1)
        coEvery { repository.getById(pending.id) } returns pending
        coEvery {
            repository.transition(pending.id, pending.version, ProfileRelationshipRequestStatus.DECLINED)
        } returns declined

        assertEquals(declined, service.decline(pending.id))
        coVerify(exactly = 0) { relationshipService.addRelationship(any(), any(), any(), any()) }
    }

    @Test
    fun `cancel transitions a pending request without creating a relationship`() = runTest {
        val pending = relationshipRequest()
        val cancelled = pending.copy(status = ProfileRelationshipRequestStatus.CANCELLED, version = 1)
        coEvery { repository.getById(pending.id) } returns pending
        coEvery {
            repository.transition(pending.id, pending.version, ProfileRelationshipRequestStatus.CANCELLED)
        } returns cancelled

        assertEquals(cancelled, service.cancel(pending.id))
    }

    @Test
    fun `transition rejects a missing request`() = runTest {
        val id = UUID.random()
        coEvery { repository.getById(id) } returns null

        assertFailsWith<NoSuchElementException> { service.decline(id) }
    }

    @Test
    fun `transition rejects a request that is no longer pending`() = runTest {
        val approved = relationshipRequest(status = ProfileRelationshipRequestStatus.APPROVED)
        coEvery { repository.getById(approved.id) } returns approved

        val exception = assertFailsWith<IllegalStateException> { service.cancel(approved.id) }

        assertEquals("relationship request is not pending", exception.message)
    }

    @Test
    fun `transition rejects a concurrent request update`() = runTest {
        val pending = relationshipRequest()
        coEvery { repository.getById(pending.id) } returns pending
        coEvery {
            repository.transition(pending.id, pending.version, ProfileRelationshipRequestStatus.DECLINED)
        } returns null

        val exception = assertFailsWith<IllegalStateException> { service.decline(pending.id) }

        assertEquals("relationship request was modified concurrently", exception.message)
    }

    @Test
    fun `request pages reject negative offsets and limits`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.getIncoming(UUID.random(), null, offset = -1, limit = 10)
        }
        assertFailsWith<IllegalArgumentException> {
            service.getOutgoing(UUID.random(), null, offset = 0, limit = -1)
        }
    }

    private fun relationshipRequest(
        requesterProfileId: UUID = UUID.random(),
        targetProfileId: UUID = UUID.random(),
        status: ProfileRelationshipRequestStatus = ProfileRelationshipRequestStatus.PENDING,
        attributes: kotlinx.serialization.json.JsonElement? = null,
    ) = ProfileRelationshipRequest(
        id = UUID.random(),
        requesterProfileId = requesterProfileId,
        targetProfileId = targetProfileId,
        type = "friend",
        attributes = attributes,
        status = status,
        version = 0,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )
}
