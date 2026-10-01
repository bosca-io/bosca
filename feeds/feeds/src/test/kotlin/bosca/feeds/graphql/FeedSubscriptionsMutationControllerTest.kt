@file:OptIn(ExperimentalUuidApi::class)

package bosca.feeds.graphql

import bosca.feeds.model.FeedSource
import bosca.feeds.service.FeedSubscriptionService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**subscription mutations resolve the caller's profile (via ProfileService) then delegate. */
class FeedSubscriptionsMutationControllerTest {

    private val feedSubscriptionService = mockk<FeedSubscriptionService>()
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val controller = FeedSubscriptionsMutationController(feedSubscriptionService, profileService)

    private fun authFor(principalId: UUID, profileId: UUID): AuthenticationContext {
        val principal = Principal(id = principalId)
        coEvery { profileService.getPrimaryProfile(principal) } returns
            Profile(id = profileId, type = ProfileType.GENERIC, principal = principalId, name = "T", visibility = ProfileVisibility.PUBLIC)
        return mockk { every { principal() } returns mockk<AuthenticatedPrincipal> { every { asPrincipal() } returns principal } }
    }

    @Test
    fun `subscribe resolves the caller profile and delegates`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val sourceId = UUID.random()
        val auth = authFor(principalId, profileId)
        val source = FeedSource(sourceId = sourceId, url = "x")
        coEvery { feedSubscriptionService.subscribe(profileId, sourceId) } returns source

        assertEquals(source, controller.subscribe(auth, sourceId))
        coVerify(exactly = 1) { feedSubscriptionService.subscribe(profileId, sourceId) }
    }

    @Test
    fun `unsubscribe resolves the caller profile and delegates`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val sourceId = UUID.random()
        val auth = authFor(principalId, profileId)
        coEvery { feedSubscriptionService.unsubscribe(profileId, sourceId) } returns true

        assertEquals(true, controller.unsubscribe(auth, sourceId))
        coVerify(exactly = 1) { feedSubscriptionService.unsubscribe(profileId, sourceId) }
    }
}
