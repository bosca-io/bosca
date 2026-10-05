@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.chat.service

import bosca.chat.events.CHAT_CHANNEL_MEMBER_REMOVED_TOPIC
import bosca.chat.events.CHAT_PROFILE_UNAVAILABLE_TOPIC
import bosca.chat.events.ChatChannelMemberRemovedEvent
import bosca.chat.events.ChatProfileUnavailableEvent
import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatChannelRoles
import bosca.chat.model.ChatChannelType
import bosca.chat.repository.ChatChannelInvitationRepository
import bosca.chat.repository.ChatChannelRepository
import bosca.chat.state.ChatPresenceStore
import bosca.chat.state.ChatReactionStore
import bosca.chat.state.ChatReadStateStore
import bosca.chat.state.ChatTypingStore
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pubsub.PubSubService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class ChatProfileCleanupHandlerTest {

    private val channelRepository = mockk<ChatChannelRepository>()
    private val invitationRepository = mockk<ChatChannelInvitationRepository>()
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val pubSubService = mockk<PubSubService>(relaxed = true)
    private val reactionStore = mockk<ChatReactionStore>(relaxed = true)
    private val readStateStore = mockk<ChatReadStateStore>(relaxed = true)
    private val typingStore = mockk<ChatTypingStore>(relaxed = true)
    private val presenceStore = mockk<ChatPresenceStore>(relaxed = true)
    private val handler = ChatProfileCleanupHandler(
        channelRepository,
        invitationRepository,
        securityService,
        reactionStore,
        readStateStore,
        typingStore,
        presenceStore,
    )

    @BeforeTest
    fun setUp() {
        ProviderRegistry.clear()
        provides<PubSubService> { pubSubService }
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction<Any?>(any()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        coEvery {
            channelRepository.hasOtherMemberForPrincipal(any(), any(), any(), any())
        } returns false
        coEvery { channelRepository.getChannelsByProfileId(any()) } returns emptyList()
        coEvery { channelRepository.getMembershipsByProfileId(any()) } returns emptyList()
        coEvery { invitationRepository.cancelPendingByProfile(any()) } returns 0
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        ProviderRegistry.clear()
    }

    @Test
    fun `profile deletion removes principal from all remaining channel groups`() = runTest {
        val profileId = UUID.random()
        val principalId = UUID.random()
        val channelId = UUID.random()
        val users = channelGroup(channelId, administrators = false)
        val administrators = channelGroup(channelId, administrators = true)
        val unrelated = Group(UUID.random(), "messaging", "Messaging", GroupType.SYSTEM)
        coEvery { channelRepository.getIncompleteDirectChannels() } returns emptyList()
        coEvery { securityService.getPrincipalGroups(principalId) } returns
            listOf(users, administrators, unrelated)

        handler.onProfileCleanup(profileId, principalId)

        coVerify { securityService.removePrincipalGroup(principalId, users.id) }
        coVerify { securityService.removePrincipalGroup(principalId, administrators.id) }
        coVerify(exactly = 0) { securityService.removePrincipalGroup(principalId, unrelated.id) }
        verifyProfileStateCleared(profileId)
        verifyProfileDeletionEvent(profileId)
    }

    @Test
    fun `profile deletion preserves groups required by another profile of the principal`() = runTest {
        val profileId = UUID.random()
        val principalId = UUID.random()
        val channelId = UUID.random()
        val users = channelGroup(channelId, administrators = false)
        val administrators = channelGroup(channelId, administrators = true)
        coEvery { channelRepository.getIncompleteDirectChannels() } returns emptyList()
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(users, administrators)
        coEvery {
            channelRepository.hasOtherMemberForPrincipal(channelId, principalId, profileId, null)
        } returns true
        coEvery {
            channelRepository.hasOtherMemberForPrincipal(
                channelId,
                principalId,
                profileId,
                ChatChannelRoles.ADMIN,
            )
        } returns true

        handler.onProfileCleanup(profileId, principalId)

        coVerify(exactly = 0) { securityService.removePrincipalGroup(principalId, any()) }
    }

    @Test
    fun `direct channel deletion clears its state closes survivor and deletes its security groups`() = runTest {
        val deletedProfileId = UUID.random()
        val survivingProfileId = UUID.random()
        val channel = ChatChannel(UUID.random(), name = "DM", type = ChatChannelType.DIRECT)
        val users = channelGroup(channel.id, administrators = false)
        val administrators = channelGroup(channel.id, administrators = true)
        coEvery { channelRepository.getIncompleteDirectChannels() } returns listOf(channel)
        coEvery { channelRepository.getMembers(channel.id) } returns listOf(
            ChatChannelMember(channel.id, survivingProfileId, ChatChannelRoles.MEMBER),
        )
        coEvery { channelRepository.deleteChannel(channel.id) } returns 1
        coEvery {
            securityService.getGroupByName(ChatChannelSecurityGroups.usersName(channel.id), GroupType.SYSTEM)
        } returns users
        coEvery {
            securityService.getGroupByName(
                ChatChannelSecurityGroups.administratorsName(channel.id),
                GroupType.SYSTEM,
            )
        } returns administrators

        handler.onProfileCleanup(deletedProfileId, null)

        coVerify { reactionStore.clearChannel(channel.id) }
        coVerify { readStateStore.clearChannel(channel.id) }
        coVerify { typingStore.clearChannel(channel.id) }
        coVerify { securityService.deleteGroup(users.id) }
        coVerify { securityService.deleteGroup(administrators.id) }
        verifyProfileStateCleared(deletedProfileId)
        verifyRemovalEvent(ChatChannelMemberRemovedEvent(channel.id, survivingProfileId))
        verifyProfileDeletionEvent(deletedProfileId)
    }

    @Test
    fun `deletion without a principal still clears profile state and subscriptions`() = runTest {
        val profileId = UUID.random()
        coEvery { channelRepository.getIncompleteDirectChannels() } returns emptyList()

        handler.onProfileCleanup(profileId, null)

        coVerify(exactly = 0) { securityService.getPrincipalGroups(any<UUID>()) }
        verifyProfileStateCleared(profileId)
        verifyProfileDeletionEvent(profileId)
    }

    @Test
    fun `administrative unlink removes retained non-direct memberships`() = runTest {
        val profileId = UUID.random()
        val principalId = UUID.random()
        val channel = ChatChannel(UUID.random(), name = "Group", type = ChatChannelType.GROUP)
        val membership = ChatChannelMember(channel.id, profileId, ChatChannelRoles.MEMBER)
        val users = channelGroup(channel.id, administrators = false)
        coEvery { channelRepository.getChannelsByProfileId(profileId) } returns listOf(channel)
        coEvery { channelRepository.getMembershipsByProfileId(profileId) } returns listOf(membership)
        coEvery { channelRepository.getIncompleteDirectChannels() } returns emptyList()
        coEvery { channelRepository.removeMember(channel.id, profileId) } returns 1
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(users)

        handler.onProfileCleanup(profileId, principalId)

        coVerify(exactly = 1) { channelRepository.removeMember(channel.id, profileId) }
        coVerify(exactly = 1) { securityService.removePrincipalGroup(principalId, users.id) }
        verifyRemovalEvent(ChatChannelMemberRemovedEvent(channel.id, profileId))
        verifyProfileDeletionEvent(profileId)
    }

    @Test
    fun `cleanup cancels pending invitations sent by or addressed to the profile`() = runTest {
        val profileId = UUID.random()
        coEvery { channelRepository.getIncompleteDirectChannels() } returns emptyList()
        coEvery { invitationRepository.cancelPendingByProfile(profileId) } returns 2

        handler.onProfileCleanup(profileId, null)

        coVerify(exactly = 1) { invitationRepository.cancelPendingByProfile(profileId) }
    }

    private fun verifyProfileStateCleared(profileId: UUID) {
        coVerify { reactionStore.clearProfile(profileId) }
        coVerify { readStateStore.clearProfile(profileId) }
        coVerify { typingStore.clearProfile(profileId) }
        coVerify { presenceStore.clearPresence(profileId) }
    }

    private fun verifyRemovalEvent(event: ChatChannelMemberRemovedEvent) {
        coVerify {
            pubSubService.publish(
                CHAT_CHANNEL_MEMBER_REMOVED_TOPIC,
                ChatChannelMemberRemovedEvent.serializer(),
                event,
            )
        }
    }

    private fun verifyProfileDeletionEvent(profileId: UUID) {
        coVerify {
            pubSubService.publish(
                CHAT_PROFILE_UNAVAILABLE_TOPIC,
                ChatProfileUnavailableEvent.serializer(),
                ChatProfileUnavailableEvent(profileId),
            )
        }
    }

    private fun channelGroup(channelId: UUID, administrators: Boolean): Group {
        val name = if (administrators) {
            ChatChannelSecurityGroups.administratorsName(channelId)
        } else {
            ChatChannelSecurityGroups.usersName(channelId)
        }
        return Group(UUID.random(), name, name, GroupType.SYSTEM)
    }
}
