@file:OptIn(
    bosca.core.annotations.Internal::class,
    bosca.di.annotation.InternalDI::class,
)

package bosca.chat.service

import bosca.chat.events.CHAT_CHANNEL_MEMBER_REMOVED_TOPIC
import bosca.chat.events.ChatChannelJoinedEvent
import bosca.chat.events.ChatChannelMemberRemovedEvent
import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatChannelRoles
import bosca.chat.model.ChatChannelType
import bosca.chat.repository.ChatChannelPermissionRepository
import bosca.chat.repository.ChatChannelRepository
import bosca.chat.state.ChatPresenceStore
import bosca.chat.state.ChatReactionStore
import bosca.chat.state.ChatReadStateStore
import bosca.chat.state.ChatTypingStore
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.events.Event
import bosca.nats.NatsConnectionPool
import bosca.pipelines.PipelineEventDispatcher
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.pubsub.PubSubService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
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

class ChatServiceMembershipTest {

    private val repository = mockk<ChatChannelRepository>()
    private val permissionRepository = mockk<ChatChannelPermissionRepository>(relaxed = true)
    private val profileService = mockk<ProfileService>()
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val pubsub = mockk<PubSubService>(relaxed = true)
    private val typingStore = mockk<ChatTypingStore>(relaxed = true)
    private val readStateStore = mockk<ChatReadStateStore>(relaxed = true)
    private val service = ChatServiceImpl(
        nats = mockk<NatsConnectionPool>(),
        chatChannelRepository = repository,
        chatChannelPermissionRepository = permissionRepository,
        reactionStore = mockk<ChatReactionStore>(),
        typingStore = typingStore,
        presenceStore = mockk<ChatPresenceStore>(),
        readStateStore = readStateStore,
        profileService = profileService,
        securityService = securityService,
        pubSubService = pubsub,
        json = Json,
    )
    private val groups = mutableMapOf<String, Group>()
    private val pipelineEvents = mutableListOf<Event>()

    @BeforeTest
    fun setUp() {
        groups.clear()
        pipelineEvents.clear()
        ProviderRegistry.clear()
        provides<Json> { Json }
        provides<PubSubService> { pubsub }
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
        coEvery { bosca.db.transaction<Any?>(any()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        coEvery { securityService.addGroup(any()) } coAnswers {
            firstArg<Group>().let { input ->
                input.copy(id = input.id.takeUnless { it == UUID.NIL } ?: UUID.random())
            }.also { groups[it.name] = it }
        }
        coEvery { securityService.getGroupByName(any(), GroupType.SYSTEM) } coAnswers {
            groups[firstArg<String>()]
        }
        coEvery {
            repository.hasOtherMemberForPrincipal(any(), any(), any(), any())
        } returns false
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
        ProviderRegistry.clear()
    }

    @Test
    fun `member join adds profile membership and principal to channel users group`() = runTest {
        val channelId = UUID.random()
        val profileId = UUID.random()
        val principalId = UUID.random()
        val users = addChannelGroup(channelId, administrators = false)
        stubChannel(channelId)
        stubEligibleProfile(profileId, principalId)
        coEvery { repository.addMemberIfAbsent(channelId, profileId, ChatChannelRoles.MEMBER) } returns 1

        service.joinChannel(channelId, profileId, ChatChannelRoles.MEMBER)

        coVerify { securityService.addPrincipalGroup(principalId, users.id) }
        coVerify(exactly = 0) {
            securityService.addPrincipalGroup(principalId, match { it != users.id })
        }
        assertEquals(listOf(profileId), pipelineEvents.filterIsInstance<ChatChannelJoinedEvent>().map { it.profileId })
    }

    @Test
    fun `administrator join adds principal to channel user and administrator groups`() = runTest {
        val channelId = UUID.random()
        val profileId = UUID.random()
        val principalId = UUID.random()
        val users = addChannelGroup(channelId, administrators = false)
        val administrators = addChannelGroup(channelId, administrators = true)
        stubChannel(channelId)
        stubEligibleProfile(profileId, principalId)
        coEvery { repository.addMemberIfAbsent(channelId, profileId, ChatChannelRoles.ADMIN) } returns 1

        service.joinChannel(channelId, profileId, ChatChannelRoles.ADMIN, notifyExistingMembers = false)

        coVerify { securityService.addPrincipalGroup(principalId, users.id) }
        coVerify { securityService.addPrincipalGroup(principalId, administrators.id) }
        assertEquals(emptyList(), pipelineEvents)
    }

    @Test
    fun `repeated join preserves the stored role`() = runTest {
        val channelId = UUID.random()
        val profileId = UUID.random()
        val principalId = UUID.random()
        val users = addChannelGroup(channelId, administrators = false)
        stubChannel(channelId)
        stubEligibleProfile(profileId, principalId)
        coEvery { repository.addMemberIfAbsent(channelId, profileId, ChatChannelRoles.ADMIN) } returns 0
        coEvery { repository.getMember(channelId, profileId) } returns
            ChatChannelMember(channelId, profileId, ChatChannelRoles.MEMBER)

        service.joinChannel(channelId, profileId, ChatChannelRoles.ADMIN)

        coVerify { securityService.addPrincipalGroup(principalId, users.id) }
        coVerify(exactly = 0) {
            securityService.addPrincipalGroup(principalId, match { it != users.id })
        }
        assertEquals(emptyList(), pipelineEvents)
    }

    @Test
    fun `join allows active profile without messaging group`() = runTest {
        val channelId = UUID.random()
        val profileId = UUID.random()
        val principalId = UUID.random()
        val users = addChannelGroup(channelId, administrators = false)
        stubChannel(channelId)
        coEvery { profileService.getById(profileId) } returns profile(profileId, principalId)
        coEvery { securityService.getPrincipalById(principalId) } returns Principal(id = principalId)
        coEvery { securityService.getPrincipalGroups(principalId) } returns emptyList()
        coEvery { repository.addMemberIfAbsent(channelId, profileId, ChatChannelRoles.MEMBER) } returns 1

        service.joinChannel(channelId, profileId, ChatChannelRoles.MEMBER)

        coVerify(exactly = 1) { repository.addMemberIfAbsent(channelId, profileId, ChatChannelRoles.MEMBER) }
        coVerify(exactly = 1) { securityService.addPrincipalGroup(principalId, users.id) }
    }

    @Test
    fun `member leave removes membership and principal from channel users group`() = runTest {
        val channelId = UUID.random()
        val profileId = UUID.random()
        val principalId = UUID.random()
        val users = addChannelGroup(channelId, administrators = false)
        stubChannel(channelId)
        coEvery { repository.getMember(channelId, profileId) } returns
            ChatChannelMember(channelId, profileId, ChatChannelRoles.MEMBER)
        coEvery { profileService.getById(profileId) } returns profile(profileId, principalId)
        coEvery { repository.removeMember(channelId, profileId) } returns 1

        service.leaveChannel(channelId, profileId)

        coVerify { securityService.removePrincipalGroup(principalId, users.id) }
        coVerify { readStateStore.clear(channelId, profileId) }
        coVerify { typingStore.clearTyping(channelId, profileId) }
        coVerify {
            pubsub.publish(
                CHAT_CHANNEL_MEMBER_REMOVED_TOPIC,
                ChatChannelMemberRemovedEvent.serializer(),
                ChatChannelMemberRemovedEvent(channelId, profileId),
            )
        }
    }

    @Test
    fun `administrator removal removes principal from both channel groups`() = runTest {
        val channelId = UUID.random()
        val profileId = UUID.random()
        val principalId = UUID.random()
        val users = addChannelGroup(channelId, administrators = false)
        val administrators = addChannelGroup(channelId, administrators = true)
        stubChannel(channelId)
        coEvery { repository.getMember(channelId, profileId) } returns
            ChatChannelMember(channelId, profileId, ChatChannelRoles.ADMIN)
        coEvery { profileService.getById(profileId) } returns profile(profileId, principalId)
        coEvery { repository.removeMember(channelId, profileId) } returns 1

        service.removeMember(channelId, profileId)

        coVerify { securityService.removePrincipalGroup(principalId, users.id) }
        coVerify { securityService.removePrincipalGroup(principalId, administrators.id) }
    }

    @Test
    fun `leaving through one profile preserves access required by another owned profile`() = runTest {
        val channelId = UUID.random()
        val profileId = UUID.random()
        val principalId = UUID.random()
        val users = addChannelGroup(channelId, administrators = false)
        stubChannel(channelId)
        coEvery { repository.getMember(channelId, profileId) } returns
            ChatChannelMember(channelId, profileId, ChatChannelRoles.MEMBER)
        coEvery { profileService.getById(profileId) } returns profile(profileId, principalId)
        coEvery { repository.removeMember(channelId, profileId) } returns 1
        coEvery {
            repository.hasOtherMemberForPrincipal(channelId, principalId, profileId, null)
        } returns true

        service.leaveChannel(channelId, profileId)

        coVerify(exactly = 0) { securityService.removePrincipalGroup(principalId, users.id) }
    }

    @Test
    fun `promotion and demotion directly synchronize administrator group`() = runTest {
        val channelId = UUID.random()
        val profileId = UUID.random()
        val principalId = UUID.random()
        val administrators = addChannelGroup(channelId, administrators = true)
        stubChannel(channelId)
        coEvery { profileService.getById(profileId) } returns profile(profileId, principalId)
        coEvery { repository.getMember(channelId, profileId) } returnsMany listOf(
            ChatChannelMember(channelId, profileId, ChatChannelRoles.MEMBER),
            ChatChannelMember(channelId, profileId, ChatChannelRoles.ADMIN),
        )
        coEvery { repository.updateMemberRole(channelId, profileId, any()) } returns 1
        service.setMemberRole(channelId, profileId, ChatChannelRoles.ADMIN)
        service.setMemberRole(channelId, profileId, ChatChannelRoles.MEMBER)

        coVerify { securityService.addPrincipalGroup(principalId, administrators.id) }
        coVerify { securityService.removePrincipalGroup(principalId, administrators.id) }
    }

    @Test
    fun `promotion rejects a soft-deleted member before changing its role`() = runTest {
        val channelId = UUID.random()
        val profileId = UUID.random()
        val principalId = UUID.random()
        stubChannel(channelId)
        coEvery { repository.getMember(channelId, profileId) } returns
            ChatChannelMember(channelId, profileId, ChatChannelRoles.MEMBER)
        coEvery { profileService.getById(profileId) } returns profile(
            profileId,
            principalId,
            deleted = true,
        )

        assertFailsWith<IllegalStateException> {
            service.setMemberRole(channelId, profileId, ChatChannelRoles.ADMIN)
        }

        coVerify(exactly = 0) { repository.updateMemberRole(any(), any(), any()) }
        coVerify(exactly = 0) { securityService.addPrincipalGroup(any(), any()) }
    }

    @Test
    fun `administrator can leave and is removed from both security groups`() = runTest {
        val channelId = UUID.random()
        val profileId = UUID.random()
        val principalId = UUID.random()
        val users = addChannelGroup(channelId, administrators = false)
        val administrators = addChannelGroup(channelId, administrators = true)
        stubChannel(channelId)
        coEvery { repository.getMember(channelId, profileId) } returns
            ChatChannelMember(channelId, profileId, ChatChannelRoles.ADMIN)
        coEvery { profileService.getById(profileId) } returns profile(profileId, principalId)
        coEvery { repository.removeMember(channelId, profileId) } returns 1

        service.leaveChannel(channelId, profileId)

        coVerify { repository.removeMember(channelId, profileId) }
        coVerify { securityService.removePrincipalGroup(principalId, users.id) }
        coVerify { securityService.removePrincipalGroup(principalId, administrators.id) }
    }

    @Test
    fun `channel creation provisions standard ACL groups and initial administrator`() = runTest {
        val channel = ChatChannel(UUID.random(), name = "General", type = ChatChannelType.GROUP)
        val profileId = UUID.random()
        val principalId = UUID.random()
        coEvery { repository.createChannel(null, "General", ChatChannelType.GROUP, null) } returns channel
        stubEligibleProfile(profileId, principalId)
        coEvery { repository.addMemberIfAbsent(channel.id, profileId, ChatChannelRoles.ADMIN) } returns 1

        val created = service.createChannel(
            groupId = null,
            name = "General",
            type = ChatChannelType.GROUP,
            attributes = null,
            dispatchCreatedEvent = false,
            initialMemberProfileId = profileId,
            initialMemberRole = ChatChannelRoles.ADMIN,
        )

        assertEquals(channel, created)
        val users = groups.getValue(ChatChannelSecurityGroups.usersName(channel.id))
        val administrators = groups.getValue(ChatChannelSecurityGroups.administratorsName(channel.id))
        coVerify { permissionRepository.addPermission(channel.id, users.id, PermissionAction.VIEW) }
        coVerify { permissionRepository.addPermission(channel.id, users.id, PermissionAction.EXECUTE) }
        coVerify { permissionRepository.addPermission(channel.id, administrators.id, PermissionAction.MANAGE) }
        coVerify { securityService.addPrincipalGroup(principalId, users.id) }
        coVerify { securityService.addPrincipalGroup(principalId, administrators.id) }
    }

    private fun stubChannel(channelId: UUID) {
        coEvery { repository.getByIdForUpdate(channelId) } returns
            ChatChannel(channelId, name = "Channel", type = ChatChannelType.GROUP)
    }

    private fun stubEligibleProfile(profileId: UUID, principalId: UUID) {
        coEvery { profileService.getById(profileId) } returns profile(profileId, principalId)
        coEvery { securityService.getPrincipalById(principalId) } returns Principal(id = principalId)
        coEvery { securityService.getPrincipalGroups(principalId) } returns emptyList()
    }

    private fun profile(profileId: UUID, principalId: UUID, deleted: Boolean = false) = Profile(
        id = profileId,
        type = ProfileType.GENERIC,
        principal = principalId,
        name = "Profile",
        visibility = ProfileVisibility.USER,
        deletedAt = bosca.serialization.OffsetDateTime.now().takeIf { deleted },
    )

    private fun addChannelGroup(channelId: UUID, administrators: Boolean): Group {
        val name = if (administrators) {
            ChatChannelSecurityGroups.administratorsName(channelId)
        } else {
            ChatChannelSecurityGroups.usersName(channelId)
        }
        return Group(UUID.random(), name, name, GroupType.SYSTEM).also { groups[name] = it }
    }
}
