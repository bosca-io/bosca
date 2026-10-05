@file:OptIn(ExperimentalUuidApi::class)

package bosca.feeds.graphql

import bosca.feeds.model.FeedSource
import bosca.feeds.service.FeedSourceService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**per-source user mutations authorize via ProfileService + GroupEvaluator (owner or admin). */
class FeedUserSourceMutationControllerTest {

    private val feedSourceService = mockk<FeedSourceService>()
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val controller = FeedUserSourceMutationController(feedSourceService, profileService, groupEvaluator)

    private fun authFor(principalId: UUID, profileId: UUID): AuthenticationContext {
        val principal = Principal(id = principalId)
        coEvery { profileService.getPrimaryProfile(principal) } returns
            Profile(id = profileId, type = ProfileType.GENERIC, principal = principalId, name = "T", visibility = ProfileVisibility.PUBLIC)
        return mockk { every { principal() } returns mockk<AuthenticatedPrincipal> { every { asPrincipal() } returns principal } }
    }

    @Test
    fun `delete by the owner verifies ownership then deletes`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val sourceId = UUID.random()
        val auth = authFor(principalId, profileId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        coEvery { feedSourceService.get(sourceId) } returns FeedSource(sourceId = sourceId, url = "x", ownerProfileId = profileId)
        coEvery { feedSourceService.delete(sourceId) } returns true

        assertEquals(true, controller.delete(auth, FeedUserSourceMutation(sourceId)))
        coVerify(exactly = 1) { feedSourceService.delete(sourceId) }
    }

    @Test
    fun `delete rejects a non-owner and never deletes`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val sourceId = UUID.random()
        val auth = authFor(principalId, profileId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        coEvery { feedSourceService.get(sourceId) } returns FeedSource(sourceId = sourceId, url = "x", ownerProfileId = UUID.random())

        assertFailsWith<SecurityException> { controller.delete(auth, FeedUserSourceMutation(sourceId)) }
        coVerify(exactly = 0) { feedSourceService.delete(any()) }
    }

    @Test
    fun `delete allows an admin to act on another's source`() = runTest {
        val sourceId = UUID.random()
        val auth = authFor(UUID.random(), UUID.random())
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { feedSourceService.get(sourceId) } returns FeedSource(sourceId = sourceId, url = "x", ownerProfileId = UUID.random())
        coEvery { feedSourceService.delete(sourceId) } returns true

        assertEquals(true, controller.delete(auth, FeedUserSourceMutation(sourceId)))
    }
}
