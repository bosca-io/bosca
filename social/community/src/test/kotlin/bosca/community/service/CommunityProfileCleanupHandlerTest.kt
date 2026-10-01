package bosca.community.service

import bosca.community.model.CommunityGroup
import bosca.community.model.CommunityGroupType
import bosca.community.model.CommunityVisibility
import bosca.community.repository.CommunityGroupRepository
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

class CommunityProfileCleanupHandlerTest {

    private val repository = mockk<CommunityGroupRepository>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val handler = CommunityProfileCleanupHandler(repository, securityService)

    @BeforeTest
    fun setUp() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction<Any?>(any()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `cleanup removes profile memberships and all community groups from its former principal`() = runTest {
        val profileId = UUID.random()
        val principalId = UUID.random()
        val communityId = UUID.random()
        val community = CommunityGroup(
            id = communityId,
            name = "Community",
            description = "Description",
            type = CommunityGroupType.SMALL_GROUP,
            visibility = CommunityVisibility.PRIVATE,
        )
        val users = Group(
            UUID.random(),
            CommunitySecurityGroups.usersName(communityId),
            "Users",
            GroupType.SYSTEM,
        )
        val administrators = Group(
            UUID.random(),
            CommunitySecurityGroups.administratorsName(communityId),
            "Administrators",
            GroupType.SYSTEM,
        )
        val unrelated = Group(UUID.random(), "messaging", "Messaging", GroupType.SYSTEM)
        coEvery { repository.getGroupsByProfileId(profileId) } returns listOf(community)
        coEvery { securityService.getPrincipalGroups(principalId) } returns
            listOf(users, administrators, unrelated)

        handler.onProfileCleanup(profileId, principalId)

        coVerify(exactly = 1) { repository.removeMember(communityId, profileId) }
        coVerify(exactly = 1) { securityService.removePrincipalGroup(principalId, users.id) }
        coVerify(exactly = 1) { securityService.removePrincipalGroup(principalId, administrators.id) }
        coVerify(exactly = 0) { securityService.removePrincipalGroup(principalId, unrelated.id) }
    }

    @Test
    fun `cleanup without a former principal still removes retained memberships`() = runTest {
        val profileId = UUID.random()
        val community = CommunityGroup(
            id = UUID.random(),
            name = "Community",
            description = "Description",
            type = CommunityGroupType.SMALL_GROUP,
            visibility = CommunityVisibility.PRIVATE,
        )
        coEvery { repository.getGroupsByProfileId(profileId) } returns listOf(community)

        handler.onProfileCleanup(profileId, null)

        coVerify(exactly = 1) { repository.removeMember(community.id, profileId) }
        coVerify(exactly = 0) { securityService.getPrincipalGroups(any<UUID>()) }
        coVerify(exactly = 0) { securityService.removePrincipalGroup(any(), any()) }
    }
}
