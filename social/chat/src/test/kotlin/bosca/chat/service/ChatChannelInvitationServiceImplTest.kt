@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.chat.service

import bosca.chat.events.ChatChannelInvitationSentEvent
import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelInvitation
import bosca.chat.model.ChatChannelInvitationStatus
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatObjectType
import bosca.chat.repository.ChatChannelInvitationRepository
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.events.Event
import bosca.pipelines.PipelineEventDispatcher
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
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatChannelInvitationServiceImplTest {

    private val repository = mockk<ChatChannelInvitationRepository>()
    private val chatService = mockk<ChatService>()
    private val service = ChatChannelInvitationServiceImpl(repository, chatService)
    private val pipelineEvents = mutableListOf<Event>()

    @BeforeTest
    fun setUp() {
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
        coEvery { transaction<ChatChannelInvitation>(any()) } coAnswers {
            firstArg<suspend () -> ChatChannelInvitation>().invoke()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        ProviderRegistry.clear()
    }

    @Test
    fun `invite persists and dispatches after boundary authorization`() = runTest {
        val invitation = invitation()
        coEvery { chatService.getById(invitation.channelId) } returns channel(invitation.channelId)
        coEvery { chatService.getMember(invitation.channelId, invitation.inviterProfileId) } returns
            ChatChannelMember(invitation.channelId, invitation.inviterProfileId, "admin")
        coEvery { chatService.getMember(invitation.channelId, invitation.inviteeProfileId) } returns null
        coEvery { chatService.canParticipate(invitation.inviteeProfileId) } returns true
        coEvery {
            repository.add(
                invitation.channelId,
                invitation.inviterProfileId,
                invitation.inviteeProfileId,
                invitation.role,
            )
        } returns invitation

        assertEquals(
            invitation,
            service.invite(
                invitation.channelId,
                invitation.inviterProfileId,
                invitation.inviteeProfileId,
            ),
        )

        coVerify(exactly = 1) { transaction<ChatChannelInvitation>(any()) }
        assertEquals(listOf(invitation.id), pipelineEvents.filterIsInstance<ChatChannelInvitationSentEvent>().map { it.invitationId })
    }

    @Test
    fun `invite rejects an inviter that is not a channel member`() = runTest {
        val invitation = invitation()
        coEvery { chatService.getById(invitation.channelId) } returns channel(invitation.channelId)
        coEvery { chatService.getMember(invitation.channelId, any()) } returns null
        coEvery { chatService.canParticipate(invitation.inviteeProfileId) } returns true
        coEvery {
            repository.add(
                invitation.channelId,
                invitation.inviterProfileId,
                invitation.inviteeProfileId,
                invitation.role,
            )
        } returns invitation

        val error = assertFailsWith<IllegalStateException> {
            service.invite(
                invitation.channelId,
                invitation.inviterProfileId,
                invitation.inviteeProfileId,
            )
        }

        assertEquals("inviter is not a channel member", error.message)
        coVerify(exactly = 0) { repository.add(any(), any(), any(), any()) }
        assertEquals(emptyList(), pipelineEvents)
    }

    @Test
    fun `invite rejects an existing channel member`() = runTest {
        val invitation = invitation()
        coEvery { chatService.getById(invitation.channelId) } returns channel(invitation.channelId)
        coEvery { chatService.getMember(invitation.channelId, invitation.inviterProfileId) } returns
            ChatChannelMember(invitation.channelId, invitation.inviterProfileId, "admin")
        coEvery { chatService.getMember(invitation.channelId, invitation.inviteeProfileId) } returns
            ChatChannelMember(invitation.channelId, invitation.inviteeProfileId, "member")

        assertFailsWith<IllegalStateException> {
            service.invite(
                invitation.channelId,
                invitation.inviterProfileId,
                invitation.inviteeProfileId,
            )
        }
        coVerify(exactly = 0) { repository.add(any(), any(), any(), any()) }
    }

    @Test
    fun `invite rejects a profile that cannot participate in chat`() = runTest {
        val invitation = invitation()
        coEvery { chatService.getById(invitation.channelId) } returns channel(invitation.channelId)
        coEvery { chatService.getMember(invitation.channelId, invitation.inviterProfileId) } returns
            ChatChannelMember(invitation.channelId, invitation.inviterProfileId, "admin")
        coEvery { chatService.getMember(invitation.channelId, invitation.inviteeProfileId) } returns null
        coEvery { chatService.canParticipate(invitation.inviteeProfileId) } returns false

        val error = assertFailsWith<IllegalStateException> {
            service.invite(
                invitation.channelId,
                invitation.inviterProfileId,
                invitation.inviteeProfileId,
            )
        }

        assertEquals("invitee is not eligible to participate in chat", error.message)
        coVerify(exactly = 0) { repository.add(any(), any(), any(), any()) }
        assertEquals(emptyList(), pipelineEvents)
    }

    @Test
    fun `invitation pages reject limits above the service maximum`() = runTest {
        val error = assertFailsWith<IllegalArgumentException> {
            service.getIncoming(UUID.random(), 0, 101)
        }

        assertEquals("limit must not exceed 100", error.message)
        coVerify(exactly = 0) { repository.getIncoming(any(), any(), any()) }
    }

    @Test
    fun `invite cannot add a participant to a direct channel`() = runTest {
        val invitation = invitation()
        coEvery { chatService.getById(invitation.channelId) } returns
            ChatChannel(invitation.channelId, name = "DM", type = ChatChannelType.DIRECT)

        assertFailsWith<IllegalArgumentException> {
            service.invite(
                invitation.channelId,
                invitation.inviterProfileId,
                invitation.inviteeProfileId,
            )
        }

        coVerify(exactly = 0) { chatService.getMember(any(), any()) }
        coVerify(exactly = 0) { repository.add(any(), any(), any(), any()) }
    }

    @Test
    fun `invite cannot bypass an object channel's owning authorization`() = runTest {
        val invitation = invitation()
        coEvery { chatService.getById(invitation.channelId) } returns ChatChannel(
            invitation.channelId,
            name = "Private object",
            type = ChatChannelType.GROUP,
            objectType = ChatObjectType.METADATA,
            objectId = UUID.random(),
        )

        assertFailsWith<IllegalArgumentException> {
            service.invite(
                invitation.channelId,
                invitation.inviterProfileId,
                invitation.inviteeProfileId,
            )
        }

        coVerify(exactly = 0) { chatService.getMember(any(), any()) }
        coVerify(exactly = 0) { repository.add(any(), any(), any(), any()) }
    }

    @Test
    fun `accept transitions and joins through the common channel membership path`() = runTest {
        val pending = invitation()
        val accepted = pending.copy(status = ChatChannelInvitationStatus.ACCEPTED, version = 1)
        coEvery { repository.getById(pending.id) } returns pending
        coEvery {
            repository.transition(pending.id, pending.version, ChatChannelInvitationStatus.ACCEPTED)
        } returns accepted
        coEvery { chatService.getMember(accepted.channelId, accepted.inviteeProfileId) } returns null
        coEvery { chatService.joinChannel(accepted.channelId, accepted.inviteeProfileId, accepted.role) } just Runs

        assertEquals(accepted, service.accept(pending.id, pending.inviteeProfileId))

        coVerify(exactly = 1) {
            chatService.joinChannel(accepted.channelId, accepted.inviteeProfileId, accepted.role)
        }
        assertEquals(emptyList(), pipelineEvents)
    }

    @Test
    fun `accept preserves an existing membership`() = runTest {
        val pending = invitation()
        val accepted = pending.copy(status = ChatChannelInvitationStatus.ACCEPTED, version = 1)
        coEvery { repository.getById(pending.id) } returns pending
        coEvery {
            repository.transition(pending.id, pending.version, ChatChannelInvitationStatus.ACCEPTED)
        } returns accepted
        coEvery { chatService.getMember(accepted.channelId, accepted.inviteeProfileId) } returns
            ChatChannelMember(accepted.channelId, accepted.inviteeProfileId, "admin")

        assertEquals(accepted, service.accept(pending.id, pending.inviteeProfileId))

        coVerify(exactly = 0) { chatService.joinChannel(any(), any(), any()) }
    }

    @Test
    fun `decline and cancel enforce the owning profile`() = runTest {
        val pending = invitation()
        coEvery { repository.getById(pending.id) } returns pending

        assertFailsWith<IllegalStateException> { service.decline(pending.id, UUID.random()) }
        assertFailsWith<IllegalStateException> { service.cancel(pending.id, UUID.random()) }
        coVerify(exactly = 0) { repository.transition(any(), any(), any()) }
    }

    private fun invitation() = ChatChannelInvitation(
        id = UUID.random(),
        channelId = UUID.random(),
        inviterProfileId = UUID.random(),
        inviteeProfileId = UUID.random(),
        role = "member",
        status = ChatChannelInvitationStatus.PENDING,
        version = 0,
        created = OffsetDateTime.now(),
        modified = OffsetDateTime.now(),
    )

    private fun channel(id: UUID) = ChatChannel(id = id, name = "Planning", type = ChatChannelType.GROUP)
}
