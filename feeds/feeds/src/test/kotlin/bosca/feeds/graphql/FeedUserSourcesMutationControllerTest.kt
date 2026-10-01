@file:OptIn(ExperimentalUuidApi::class)

package bosca.feeds.graphql

import bosca.feeds.model.FeedConfiguration
import bosca.feeds.model.FeedSource
import bosca.feeds.model.FeedSourceInput
import bosca.feeds.model.Ownership
import bosca.feeds.model.SourceType
import bosca.feeds.service.FeedSourceService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**the user-add controller forces ownership to the caller (resolved via ProfileService). */
class FeedUserSourcesMutationControllerTest {

    private val feedSourceService = mockk<FeedSourceService>()
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val controller = FeedUserSourcesMutationController(feedSourceService, profileService)

    private fun authFor(principalId: UUID, profileId: UUID): AuthenticationContext {
        val principal = Principal(id = principalId)
        coEvery { profileService.getPrimaryProfile(principal) } returns
            Profile(id = profileId, type = ProfileType.GENERIC, principal = principalId, name = "T", visibility = ProfileVisibility.PUBLIC)
        return mockk { every { principal() } returns mockk<AuthenticatedPrincipal> { every { asPrincipal() } returns principal } }
    }

    @Test
    fun `add forces the caller's ownership onto the created source`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val auth = authFor(principalId, profileId)
        val createSlot = slot<FeedSourceInput>()
        coEvery { feedSourceService.create(capture(createSlot)) } answers {
            FeedSource(sourceId = UUID.random(), url = "https://x/f", ownerProfileId = profileId)
        }

        val input = FeedSourceInput(
            "N", "d",
            FeedConfiguration(type = SourceType.RSS, endpoint = "https://x/f", cronInterval = "0 0 * * * *"),
        )
        controller.add(auth, input)

        assertEquals(Ownership.USER, createSlot.captured.configuration.ownership)
        assertEquals(profileId, createSlot.captured.configuration.ownerProfileId)
    }
}
