package bosca.core.service

import bosca.chat.service.ChatService
import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelMember
import bosca.chat.model.ChatChannelRoles
import bosca.chat.model.ChatChannelType
import bosca.community.model.CommunityGroup
import bosca.community.model.CommunityGroupType
import bosca.community.model.CommunityVisibility
import bosca.community.repository.CommunityGroupPermissionRepository
import bosca.community.repository.CommunityGroupRepository
import bosca.community.repository.CommunityGroupSignupEmailRepository
import bosca.community.repository.CommunityGroupSignupTokenRepository
import bosca.community.service.CommunityServiceImpl
import bosca.profile.profile.service.ProfileService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.just
import io.mockk.Runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CommunityServiceImplTest {

    private val communityGroupRepository = mockk<CommunityGroupRepository>()
    private val communityGroupPermissionRepository = mockk<CommunityGroupPermissionRepository>()
    private val communityGroupSignupTokenRepository = mockk<CommunityGroupSignupTokenRepository>()
    private val communityGroupSignupEmailRepository = mockk<CommunityGroupSignupEmailRepository>()
    private val securityService = mockk<SecurityService>()
    private val profileService = mockk<ProfileService>()
    private val chatService = mockk<ChatService>()

    private val communityService = CommunityServiceImpl(
        communityGroupRepository,
        communityGroupPermissionRepository,
        communityGroupSignupTokenRepository,
        communityGroupSignupEmailRepository,
        securityService,
        profileService,
        chatService,
    )

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction<Any?>(any()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `updateGroup forwards changes to repository and returns updated group`(): Unit = runBlocking {
        val id = UUID.random()
        val updated = CommunityGroup(
            id = id,
            name = "New Name",
            description = "New Description",
            type = CommunityGroupType.SMALL_GROUP,
            visibility = CommunityVisibility.PRIVATE,
            attributes = null,
        )
        coEvery {
            communityGroupRepository.updateGroup(
                id,
                "New Name",
                "New Description",
                CommunityGroupType.SMALL_GROUP,
                CommunityVisibility.PRIVATE,
                null,
            )
        } returns updated
        coEvery { securityService.getGroupByName("community.${id}.administrators", GroupType.SYSTEM) } returns
            Group(id = UUID.random(), name = "community.${id}.administrators", description = "old admin", type = GroupType.SYSTEM)
        coEvery { securityService.getGroupByName("community.${id}.users", GroupType.SYSTEM) } returns
            Group(id = UUID.random(), name = "community.${id}.users", description = "old users", type = GroupType.SYSTEM)
        coEvery { securityService.editGroup(any()) } answers { firstArg() }

        val result = communityService.updateGroup(
            id,
            "New Name",
            "New Description",
            CommunityGroupType.SMALL_GROUP,
            CommunityVisibility.PRIVATE,
            null,
        )

        assertEquals(updated, result)
        coVerify {
            securityService.editGroup(match { it.description == "Community Group: New Name Administrators" })
            securityService.editGroup(match { it.description == "Community Group: New Name Users" })
        }
    }

    @Test
    fun `updateGroup skips security group sync when name is unchanged`(): Unit = runBlocking {
        val id = UUID.random()
        val updated = CommunityGroup(
            id = id,
            name = "Unchanged",
            description = "Refreshed Description",
            type = CommunityGroupType.FAMILY,
            visibility = CommunityVisibility.PUBLIC,
            attributes = null,
        )
        coEvery {
            communityGroupRepository.updateGroup(id, null, "Refreshed Description", null, null, null)
        } returns updated

        val result = communityService.updateGroup(id, null, "Refreshed Description", null, null, null)

        assertEquals(updated, result)
        coVerify(exactly = 0) { securityService.editGroup(any()) }
        coVerify(exactly = 0) { securityService.getGroupByName(any(), any()) }
    }

    @Test
    fun `adding community member adds profile to every channel and channel security group`() = runBlocking {
        val group = communityGroup()
        val profileId = UUID.random()
        val principalId = UUID.random()
        val profile = profile(profileId, principalId)
        val communityUsers = Group(
            UUID.random(),
            "community.${group.id}.users",
            "Community users",
            GroupType.SYSTEM,
        )
        val channel = ChatChannel(UUID.random(), group.id, "General", ChatChannelType.GROUP)
        coEvery { communityGroupRepository.getGroup(group.id) } returns group
        coEvery { communityGroupRepository.getMembers(group.id) } returns emptyList()
        coEvery { communityGroupRepository.addMember(group.id, profileId) } just Runs
        coEvery { profileService.getById(profileId) } returns profile
        coEvery {
            securityService.getGroupByName("community.${group.id}.users", GroupType.SYSTEM)
        } returns communityUsers
        coEvery { securityService.addPrincipalGroup(principalId, communityUsers.id) } just Runs
        coEvery { chatService.canParticipate(profileId) } returns true
        coEvery { chatService.getChannelsByGroupId(group.id) } returns listOf(channel)
        coEvery { chatService.joinChannel(channel.id, profileId, ChatChannelRoles.MEMBER, false) } just Runs

        communityService.addMember(group.id, profileId)

        coVerify { communityGroupRepository.addMember(group.id, profileId) }
        coVerify { securityService.addPrincipalGroup(principalId, communityUsers.id) }
        coVerify {
            chatService.joinChannel(channel.id, profileId, ChatChannelRoles.MEMBER, false)
        }
    }

    @Test
    fun `removing community member removes profile from every channel before community access`() = runBlocking {
        val group = communityGroup()
        val profileId = UUID.random()
        val principalId = UUID.random()
        val channel = ChatChannel(UUID.random(), group.id, "General", ChatChannelType.GROUP)
        val users = Group(
            UUID.random(),
            "community.${group.id}.users",
            "Community users",
            GroupType.SYSTEM,
        )
        val administrators = Group(
            UUID.random(),
            "community.${group.id}.administrators",
            "Community administrators",
            GroupType.SYSTEM,
        )
        coEvery { profileService.getById(profileId) } returns profile(profileId, principalId)
        coEvery { chatService.getChannelsByGroupId(group.id) } returns listOf(channel)
        coEvery { chatService.getMember(channel.id, profileId) } returns
            ChatChannelMember(channel.id, profileId, ChatChannelRoles.MEMBER)
        coEvery { chatService.leaveChannel(channel.id, profileId) } just Runs
        coEvery { communityGroupRepository.removeMember(group.id, profileId) } just Runs
        coEvery {
            securityService.getGroupByName("community.${group.id}.users", GroupType.SYSTEM)
        } returns users
        coEvery {
            securityService.getGroupByName("community.${group.id}.administrators", GroupType.SYSTEM)
        } returns administrators
        coEvery { securityService.removePrincipalGroup(any(), any()) } just Runs

        communityService.removeMember(group.id, profileId)

        coVerify { chatService.leaveChannel(channel.id, profileId) }
        coVerify { communityGroupRepository.removeMember(group.id, profileId) }
        coVerify { securityService.removePrincipalGroup(principalId, users.id) }
        coVerify { securityService.removePrincipalGroup(principalId, administrators.id) }
    }

    private fun communityGroup() = CommunityGroup(
        id = UUID.random(),
        name = "Community",
        description = "Community",
        type = CommunityGroupType.SMALL_GROUP,
        visibility = CommunityVisibility.PRIVATE,
    )

    private fun profile(profileId: UUID, principalId: UUID) = Profile(
        id = profileId,
        type = ProfileType.GENERIC,
        principal = principalId,
        name = "Profile",
        visibility = ProfileVisibility.USER,
    )
}
