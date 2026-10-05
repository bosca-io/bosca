package bosca.profile.security

import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.model.OrganizationPermission
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityException
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ProfilePermissionEvaluatorTest {

    private val profileService = mockk<ProfileService>()
    private val securityService = mockk<SecurityService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    init {
        every { groupEvaluator.hasScope(any(), any()) } returns true
    }

    @Test
    fun `relationship ownership cannot bypass token scope`() = runTest {
        val ownerId = UUID.random()
        val profile = profile(principalId = ownerId)
        fun scopedAuthentication(scope: String) = mockk<AuthenticationContext> {
            every { principal() } returns ScopedAuthenticatedPrincipal(
                Principal(id = ownerId, anonymous = false), emptyList(), listOf(scope), null, 1,
            )
        }
        val evaluator = ProfilePermissionEvaluator(profileService, securityService, GroupEvaluator(securityService))
        assertFalse(evaluator.canManageRelationships(scopedAuthentication("profiles:read"), profile))
        assertTrue(evaluator.canManageRelationships(scopedAuthentication("profiles:edit"), profile))
        coVerify(exactly = 0) { profileService.getPermissions(any()) }
    }

    @Test
    fun `constructor creates evaluator with correct dependencies`() {
        val evaluator = ProfilePermissionEvaluator(
            profileService,
            securityService,
            groupEvaluator
        )

        assertNotNull(evaluator)
    }

    @Test
    fun `service property returns profile service`() {
        val evaluator = ProfilePermissionEvaluator(
            profileService,
            securityService,
            groupEvaluator
        )

        assertSame(profileService, evaluator.service)
    }

    @Test
    fun `evaluator extends PermissionEvaluator`() {
        val evaluator = ProfilePermissionEvaluator(
            profileService,
            securityService,
            groupEvaluator
        )

        assertTrue(evaluator is bosca.security.service.PermissionEvaluator<*, *>)
    }

    @Test
    fun `relationship management allows a profile's direct owner`() = runTest {
        val ownerId = UUID.random()
        val profile = profile(principalId = ownerId)
        val authentication = authentication(ownerId)
        every { groupEvaluator.hasSaGroup(authentication) } returns false

        assertTrue(evaluator().canManageRelationships(authentication, profile))
        coVerify(exactly = 0) { profileService.getPermissions(any()) }
    }

    @Test
    fun `relationship management allows an explicitly assigned manage group`() = runTest {
        val principalId = UUID.random()
        val groupId = UUID.random()
        val profile = profile(type = ProfileType.ORGANIZATION)
        val authentication = authentication(principalId, groupId)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.getPermissions(profile) } returns listOf(
            OrganizationPermission(UUID.random(), groupId, PermissionAction.MANAGE),
        )

        assertTrue(evaluator().canManageRelationships(authentication, profile))
    }

    @Test
    fun `relationship management does not inherit global editor access`() = runTest {
        val profile = profile()
        val authentication = authentication(UUID.random())
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        every { groupEvaluator.hasEditorGroup(authentication) } returns true
        coEvery { profileService.getPermissions(profile) } returns emptyList()

        assertFalse(evaluator().canManageRelationships(authentication, profile))
    }

    @Test
    fun `relationship management allows platform administrators`() = runTest {
        val profile = profile()
        val authentication = authentication(UUID.random())
        every { groupEvaluator.hasSaGroup(authentication) } returns true

        assertTrue(evaluator().canManageRelationships(authentication, profile))
        coVerify(exactly = 0) { profileService.getPermissions(any()) }
    }

    @Test
    fun `profile owner cannot act as a soft-deleted profile`() = runTest {
        val ownerId = UUID.random()
        val profile = profile(principalId = ownerId, deletedAt = OffsetDateTime.now())
        val authentication = authentication(ownerId)
        every { groupEvaluator.hasSaGroup(authentication) } returns false

        assertFalse(evaluator().canManageRelationships(authentication, profile))
        coVerify(exactly = 0) { profileService.getPermissions(any()) }
    }

    @Test
    fun `platform administrator cannot act as a soft-deleted profile`() = runTest {
        val profile = profile(deletedAt = OffsetDateTime.now())
        val authentication = authentication(UUID.random())
        every { groupEvaluator.hasSaGroup(authentication) } returns true

        assertFalse(evaluator().canManageRelationships(authentication, profile))
    }

    @Test
    fun `relationship management rejects callers without ownership or manage permission`() = runTest {
        val profile = profile()
        val authentication = authentication(UUID.random())
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.getPermissions(profile) } returns emptyList()

        assertFailsWith<SecurityException> {
            evaluator().verifyCanManageRelationships(authentication, profile)
        }
    }

    @Test
    fun `relationship management rejects missing authentication and principals`() = runTest {
        val profile = profile()
        every { groupEvaluator.hasSaGroup(null) } returns false
        assertFalse(evaluator().canManageRelationships(null, profile))

        val authentication = mockk<AuthenticationContext>()
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        every { authentication.principal() } returns null
        assertFalse(evaluator().canManageRelationships(authentication, profile))
    }

    @Test
    fun `relationship management scans explicit permissions for manage access`() = runTest {
        val principalId = UUID.random()
        val unrelatedGroupId = UUID.random()
        val managedGroupId = UUID.random()
        val profile = profile(type = ProfileType.ORGANIZATION)
        val authentication = authentication(principalId, managedGroupId)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.getPermissions(profile) } returns listOf(
            OrganizationPermission(UUID.random(), unrelatedGroupId, PermissionAction.VIEW),
            OrganizationPermission(UUID.random(), managedGroupId, PermissionAction.MANAGE),
        )

        assertTrue(evaluator().canManageRelationships(authentication, profile))
    }

    @Test
    fun `relationship management verification accepts an owner`() = runTest {
        val principalId = UUID.random()
        val profile = profile(principalId = principalId)
        val authentication = authentication(principalId)
        every { groupEvaluator.hasSaGroup(authentication) } returns false

        evaluator().verifyCanManageRelationships(authentication, profile)
    }

    @Test
    fun `relationship participant management accepts either endpoint`() = runTest {
        val principalId = UUID.random()
        val source = profile()
        val target = profile(principalId = principalId)
        val authentication = authentication(principalId)
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.getPermissions(source) } returns emptyList()

        evaluator().verifyCanManageEitherRelationshipParticipant(authentication, source, target)
    }

    @Test
    fun `relationship participant management accepts the first endpoint`() = runTest {
        val principalId = UUID.random()
        val source = profile(principalId = principalId)
        val target = profile()
        val authentication = authentication(principalId)
        every { groupEvaluator.hasSaGroup(authentication) } returns false

        evaluator().verifyCanManageEitherRelationshipParticipant(authentication, source, target)
        coVerify(exactly = 0) { profileService.getPermissions(target) }
    }

    @Test
    fun `relationship participant management rejects callers who manage neither endpoint`() = runTest {
        val source = profile()
        val target = profile()
        val authentication = authentication(UUID.random())
        every { groupEvaluator.hasSaGroup(authentication) } returns false
        coEvery { profileService.getPermissions(source) } returns emptyList()
        coEvery { profileService.getPermissions(target) } returns emptyList()

        assertFailsWith<SecurityException> {
            evaluator().verifyCanManageEitherRelationshipParticipant(authentication, source, target)
        }
    }

    private fun evaluator() = ProfilePermissionEvaluator(
        profileService,
        securityService,
        groupEvaluator,
    )

    private fun authentication(principalId: UUID, groupId: UUID? = null): AuthenticationContext {
        val authentication = mockk<AuthenticationContext>()
        val principal = mockk<AuthenticatedPrincipal>()
        every { authentication.principal() } returns principal
        every { principal.id } returns principalId
        groupId?.let { every { principal.hasGroup(it) } returns true }
        return authentication
    }

    private fun profile(
        principalId: UUID? = null,
        type: ProfileType = ProfileType.GENERIC,
        deletedAt: OffsetDateTime? = null,
    ) = Profile(
        id = UUID.random(),
        type = type,
        principal = principalId,
        name = "Profile",
        visibility = ProfileVisibility.PUBLIC,
        deletedAt = deletedAt,
    )
}
