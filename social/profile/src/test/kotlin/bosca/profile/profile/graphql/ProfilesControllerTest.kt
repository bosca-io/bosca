package bosca.profile.profile.graphql

import bosca.profile.attribute.graphql.ProfileAttributeTypes
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ProfilesControllerTest {

    private val profileService = mockk<ProfileService>()
    private val permissionEvaluator = mockk<ProfilePermissionEvaluator>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private val controller = ProfilesController(
        profileService,
        permissionEvaluator,
        groupEvaluator
    )

    private fun createAuthContext(principalId: UUID, primaryProfileId: UUID? = null): AuthenticationContext {
        val authentication = mockk<AuthenticationContext>()
        val principal = AuthenticatedPrincipal(
            Principal(id = principalId, primaryProfileId = primaryProfileId),
            emptyList(),
        )
        every { authentication.principal() } returns principal
        return authentication
    }

    @Test
    fun `current returns anonymous profile when not authenticated`() = runTest {
        val result = controller.current(null)

        assertEquals(1, result.size)
        assertEquals(UUID.NIL, result[0].id)
        assertEquals("Anonymous", result[0].name)
        assertEquals(ProfileVisibility.PUBLIC, result[0].visibility)
        assertEquals(ProfileType.GENERIC, result[0].type)
    }

    @Test
    fun `current returns profiles for authenticated user`() = runTest {
        val principalId = UUID.random()
        val authentication = createAuthContext(principalId)
        val profiles = listOf(
            Profile(
                id = UUID.random(),
                type = ProfileType.GENERIC,
                principal = principalId,
                name = "My Profile",
                visibility = ProfileVisibility.PUBLIC
            )
        )

        coEvery { profileService.getByPrincipal(principalId) } returns profiles

        val result = controller.current(authentication)

        assertEquals(1, result.size)
        assertEquals("My Profile", result[0].name)
    }

    @Test
    fun `current returns the active primary profile first`() = runTest {
        val principalId = UUID.random()
        val primaryProfileId = UUID.random()
        val authentication = createAuthContext(principalId, primaryProfileId)
        val other = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = principalId,
            name = "Other",
            visibility = ProfileVisibility.PUBLIC,
        )
        val primary = other.copy(id = primaryProfileId, name = "Primary")
        val deleted = other.copy(
            id = UUID.random(),
            name = "Deleted",
            deletedAt = bosca.serialization.OffsetDateTime.now(),
        )
        coEvery { profileService.getByPrincipal(principalId) } returns listOf(other, deleted, primary)

        val result = controller.current(authentication)

        assertEquals(listOf(primary, other), result)
    }

    @Test
    fun `attributeTypes returns ProfileAttributeTypes singleton`() {
        val result = controller.attributeTypes()

        assertSame(ProfileAttributeTypes, result)
    }

    // ---- byGroup --------------------------------------------------------

    @Test
    fun `byGroup returns profiles when caller is in the queried group`() = runTest {
        val principalId = UUID.random()
        val auth = createAuthContext(principalId)
        every { groupEvaluator.hasGroup(auth, "messaging") } returns true
        val profile = Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            principal = UUID.random(),
            name = "Group Member",
            visibility = ProfileVisibility.PUBLIC,
        )
        coEvery { profileService.getByGroupName("messaging", 0L, 50) } returns listOf(profile)

        val result = controller.byGroup(auth, "messaging", limit = 50, offset = 0L)

        assertEquals(1, result.size)
        assertEquals("Group Member", result[0].name)
    }

    @Test
    fun `byGroup falls back to messaging-group check for the messaging group`() = runTest {
        // hasGroup returns false for "messaging" specifically (e.g. the user is in
        // administrators but not literally in messaging) — the fallback path should
        // still allow the call because hasMessagingGroup covers admins.
        val principalId = UUID.random()
        val auth = createAuthContext(principalId)
        every { groupEvaluator.hasGroup(auth, "messaging") } returns false
        every { groupEvaluator.hasMessagingGroup(auth) } returns true
        coEvery { profileService.getByGroupName("messaging", 5L, 10) } returns emptyList()

        val result = controller.byGroup(auth, "messaging", limit = 10, offset = 5L)

        assertEquals(0, result.size)
    }

    @Test
    fun `byGroup throws SecurityException when caller is in neither the group nor messaging`() = runTest {
        val auth = createAuthContext(UUID.random())
        every { groupEvaluator.hasGroup(auth, "messaging") } returns false
        every { groupEvaluator.hasMessagingGroup(auth) } returns false

        assertFailsWith<SecurityException> {
            controller.byGroup(auth, "messaging", limit = 50, offset = 0L)
        }
    }

    @Test
    fun `byGroup rejects calls for arbitrary groups when caller is not a member`() = runTest {
        // The messaging fallback is specific to the messaging group — querying
        // "administrators" without the administrators group should fail outright.
        val auth = createAuthContext(UUID.random())
        every { groupEvaluator.hasGroup(auth, "administrators") } returns false

        assertFailsWith<SecurityException> {
            controller.byGroup(auth, "administrators", limit = 50, offset = 0L)
        }
    }
}
