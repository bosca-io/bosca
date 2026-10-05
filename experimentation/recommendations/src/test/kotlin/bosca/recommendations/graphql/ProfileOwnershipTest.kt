@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.graphql

import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi

class ProfileOwnershipTest {

    private val profileService = mockk<ProfileService>()
    private val groupEvaluator = mockk<GroupEvaluator>()

    private fun authContext(principalId: UUID): AuthenticationContext {
        val principal = mockk<AuthenticatedPrincipal>()
        every { principal.id } returns principalId
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns principal
        return auth
    }

    private fun profile(id: UUID, ownerPrincipalId: UUID): Profile {
        return Profile(id = id, type = ProfileType.GENERIC, principal = ownerPrincipalId, name = "Test", visibility = ProfileVisibility.PUBLIC)
    }

    @Test
    fun `allows when principal owns the profile`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val auth = authContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        coEvery { profileService.getById(profileId) } returns profile(profileId, principalId)

        verifyProfileOwnership(auth, profileId, profileService, groupEvaluator)
    }

    @Test
    fun `rejects when the caller is not authenticated`() = runTest {
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns null // no principal → not authenticated
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false

        kotlin.test.assertFailsWith<bosca.security.service.SecurityException> {
            verifyProfileOwnership(auth, bosca.serialization.UUID.random(), profileService, groupEvaluator)
        }
    }

    @Test
    fun `rejects when principal does not own the profile`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val profileId = UUID.random()
        val auth = authContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        coEvery { profileService.getById(profileId) } returns profile(profileId, otherPrincipalId)

        assertFailsWith<SecurityException> {
            verifyProfileOwnership(auth, profileId, profileService, groupEvaluator)
        }
    }

    @Test
    fun `rejects when profile has no principal`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val auth = authContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        val unownedProfile = Profile(id = profileId, type = ProfileType.GENERIC, principal = null, name = "Unowned", visibility = ProfileVisibility.PUBLIC)
        coEvery { profileService.getById(profileId) } returns unownedProfile

        assertFailsWith<SecurityException> {
            verifyProfileOwnership(auth, profileId, profileService, groupEvaluator)
        }
    }

    @Test
    fun `rejects when authentication has no principal`() = runTest {
        val profileId = UUID.random()
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns null
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false

        assertFailsWith<SecurityException> {
            verifyProfileOwnership(auth, profileId, profileService, groupEvaluator)
        }
    }

    @Test
    fun `allows admin to access any profile`() = runTest {
        val profileId = UUID.random()
        val auth = authContext(UUID.random())
        every { groupEvaluator.hasAdminGroup(auth) } returns true

        verifyProfileOwnership(auth, profileId, profileService, groupEvaluator)
    }

    @Test
    fun `allows service account to access any profile`() = runTest {
        val profileId = UUID.random()
        val auth = authContext(UUID.random())
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns true

        verifyProfileOwnership(auth, profileId, profileService, groupEvaluator)
    }
}
