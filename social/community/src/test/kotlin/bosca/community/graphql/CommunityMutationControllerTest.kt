package bosca.community.graphql

import bosca.community.model.CommunityGroup
import bosca.community.model.CommunityGroupType
import bosca.community.model.CommunityVisibility
import bosca.community.security.CommunityGroupPermissionEvaluator
import bosca.community.security.PrayerPermissionEvaluator
import bosca.community.service.CommunityActivityService
import bosca.community.service.CommunityChatService
import bosca.community.service.CommunityService
import bosca.community.service.PrayerService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.EntityPermission
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CommunityMutationControllerTest {

    private val communityService = mockk<CommunityService>()
    private val permissionEvaluator = mockk<CommunityGroupPermissionEvaluator>()
    private val communityChatService = mockk<CommunityChatService>()
    private val profileService = mockk<ProfileService>()
    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = mockk<GroupEvaluator>()
    private val controller = CommunityMutationController(
        communityService = communityService,
        communityGroupPermissionEvaluator = permissionEvaluator,
        prayerPermissionEvaluator = mockk<PrayerPermissionEvaluator>(),
        communityChatService = communityChatService,
        groupEvaluator = groupEvaluator,
        profileService = profileService,
        communityActivityService = mockk<CommunityActivityService>(),
        prayerService = mockk<PrayerService>(),
        securityService = securityService,
    )

    private val authentication = mockk<AuthenticationContext>()
    private val entityId = UUID.random()
    private val permission = PermissionInput(PermissionAction.VIEW, entityId, UUID.random())
    private val group = CommunityGroup(
        id = entityId,
        name = "Community",
        description = "Description",
        type = CommunityGroupType.CUSTOM,
        visibility = CommunityVisibility.PRIVATE,
    )

    @Test
    fun `addPermission verifies manage access and returns the created permission`() = runTest {
        val created = mockk<EntityPermission>()
        coEvery { communityService.getGroup(entityId) } returns group
        coEvery { permissionEvaluator.verifyAllowed(authentication, group, PermissionAction.MANAGE) } just Runs
        coEvery { communityService.addPermission(permission) } returns created

        assertSame(created, controller.addPermission(authentication, permission))
        coVerify { permissionEvaluator.verifyAllowed(authentication, group, PermissionAction.MANAGE) }
        coVerify { communityService.addPermission(permission) }
    }

    @Test
    fun `deletePermission verifies manage access and returns the deleted permission`() = runTest {
        val deleted = mockk<EntityPermission>()
        coEvery { communityService.getGroup(entityId) } returns group
        coEvery { permissionEvaluator.verifyAllowed(authentication, group, PermissionAction.MANAGE) } just Runs
        coEvery { communityService.deletePermission(permission) } returns deleted

        assertSame(deleted, controller.deletePermission(authentication, permission))
        coVerify { permissionEvaluator.verifyAllowed(authentication, group, PermissionAction.MANAGE) }
        coVerify { communityService.deletePermission(permission) }
    }

    @Test
    fun `createGroup adds the authenticated principal's primary profile`() = runTest {
        val principal = Principal(id = UUID.random())
        val authenticatedPrincipal = AuthenticatedPrincipal(principal, emptyList())
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principal.id,
            name = "Primary profile",
            visibility = ProfileVisibility.USER,
        )
        val adminGroup = Group(
            id = UUID.random(),
            name = "community.admins",
            description = "Administrators",
            type = GroupType.SYSTEM,
        )
        val usersGroup = Group(
            id = UUID.random(),
            name = "community.users",
            description = "Users",
            type = GroupType.SYSTEM,
        )
        every { groupEvaluator.verifyHasMessagingAccess(authentication) } just Runs
        every { authentication.principal() } returns authenticatedPrincipal
        coEvery {
            communityService.createGroup(
                group.name,
                group.description,
                group.type,
                group.visibility,
                null,
            )
        } returns Triple(group, adminGroup, usersGroup)
        coEvery { profileService.getPrimaryProfile(principal) } returns profile
        coEvery { communityService.addMember(group.id, profile.id) } just Runs
        coEvery { securityService.addPrincipalGroup(principal.id, adminGroup.id) } just Runs
        coEvery {
            communityChatService.createGroupChannel(group.id, "general", any(), null, profile.id)
        } returns mockk()

        assertSame(
            group,
            controller.createGroup(
                authentication,
                group.name,
                group.description,
                group.type,
                group.visibility,
                null,
            ),
        )

        coVerify(exactly = 1) { profileService.getPrimaryProfile(principal) }
        coVerify(exactly = 1) { communityService.addMember(group.id, profile.id) }
    }

    @Test
    fun `createGroup rejects missing messaging access before creating the group`() = runTest {
        every { groupEvaluator.verifyHasMessagingAccess(authentication) } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.createGroup(
                authentication,
                group.name,
                group.description,
                group.type,
                group.visibility,
                null,
            )
        }

        coVerify(exactly = 0) { communityService.createGroup(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `removeMember lets the active profile leave without manage access`() = runTest {
        val profileId = UUID.random()
        val principal = Principal(id = UUID.random(), primaryProfileId = profileId)
        val authenticatedPrincipal = AuthenticatedPrincipal(principal, emptyList())
        val profile = Profile(
            id = profileId,
            type = ProfileType.GENERIC,
            principal = principal.id,
            name = "Member",
            visibility = ProfileVisibility.USER,
        )
        every { authentication.principal() } returns authenticatedPrincipal
        coEvery { communityService.getGroup(group.id) } returns group
        coEvery { profileService.getPrimaryProfile(principal) } returns profile
        coEvery { communityService.removeMember(group.id, profile.id) } just Runs

        assertTrue(controller.removeMember(authentication, group.id, profile.id))

        coVerify(exactly = 0) {
            permissionEvaluator.verifyAllowed(authentication, group, PermissionAction.MANAGE)
        }
        coVerify(exactly = 1) { communityService.removeMember(group.id, profile.id) }
    }

    @Test
    fun `removeMember still requires manage access for another profile`() = runTest {
        val activeProfileId = UUID.random()
        val principal = Principal(id = UUID.random(), primaryProfileId = activeProfileId)
        val authenticatedPrincipal = AuthenticatedPrincipal(principal, emptyList())
        val activeProfile = Profile(
            id = activeProfileId,
            type = ProfileType.GENERIC,
            principal = principal.id,
            name = "Member",
            visibility = ProfileVisibility.USER,
        )
        val otherProfileId = UUID.random()
        every { authentication.principal() } returns authenticatedPrincipal
        coEvery { communityService.getGroup(group.id) } returns group
        coEvery { profileService.getPrimaryProfile(principal) } returns activeProfile
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, group, PermissionAction.MANAGE)
        } throws SecurityException("Unauthorized access")

        assertFailsWith<SecurityException> {
            controller.removeMember(authentication, group.id, otherProfileId)
        }

        coVerify(exactly = 0) { communityService.removeMember(group.id, otherProfileId) }
    }
}
