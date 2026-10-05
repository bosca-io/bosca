@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.graphql

import bosca.content.metadata.service.MetadataService
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.rating.service.ProfileRatingService
import bosca.recommendations.model.RecommendationFeedback
import bosca.recommendations.service.RecommendationService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

class RecommendationsMutationControllerTest {

    private val recommendationService = mockk<RecommendationService>(relaxed = true)
    private val ratingService = mockk<ProfileRatingService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val metadataService = mockk<MetadataService>()
    private val controller = RecommendationsMutationController(recommendationService, ratingService, profileService, groupEvaluator, metadataService)

    private fun authenticatedContext(principalId: UUID = UUID.random()): AuthenticationContext {
        val principal = mockk<AuthenticatedPrincipal>()
        every { principal.id } returns principalId
        val auth = mockk<AuthenticationContext>()
        every { auth.principal() } returns principal
        return auth
    }

    private fun profileOwnedBy(profileId: UUID, principalId: UUID): Profile {
        return Profile(id = profileId, type = ProfileType.GENERIC, principal = principalId, name = "Test", visibility = ProfileVisibility.PUBLIC)
    }

    // --- dismiss ownership ---

    @Test
    fun `dismiss allows access when principal owns profile`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        coEvery { profileService.getById(profileId) } returns profileOwnedBy(profileId, principalId)

        val result = controller.dismiss(auth, profileId, metadataId, null)

        assertTrue(result)
        coVerify { recommendationService.dismiss(profileId, metadataId, null) }
    }

    @Test
    fun `dismiss rejects access when principal does not own profile`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        coEvery { profileService.getById(profileId) } returns profileOwnedBy(profileId, otherPrincipalId)

        assertFailsWith<SecurityException> {
            controller.dismiss(auth, profileId, UUID.random(), null)
        }
    }

    @Test
    fun `dismiss allows admin to act on any profile`() = runTest {
        val profileId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true

        val result = controller.dismiss(auth, profileId, UUID.random(), null)

        assertTrue(result)
    }

    @Test
    fun `dismiss calls service with collection id`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns true

        val result = controller.dismiss(auth, profileId, null, collectionId)

        assertTrue(result)
        coVerify { recommendationService.dismiss(profileId, null, collectionId) }
    }

    // --- undismiss ownership ---

    @Test
    fun `undismiss allows access when principal owns profile`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        coEvery { profileService.getById(profileId) } returns profileOwnedBy(profileId, principalId)

        val result = controller.undismiss(auth, profileId, metadataId, null)

        assertTrue(result)
        coVerify { recommendationService.undismiss(profileId, metadataId, null) }
    }

    @Test
    fun `undismiss rejects access when principal does not own profile`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        coEvery { profileService.getById(profileId) } returns profileOwnedBy(profileId, otherPrincipalId)

        assertFailsWith<SecurityException> {
            controller.undismiss(auth, profileId, UUID.random(), null)
        }
    }

    @Test
    fun `undismiss allows admin to act on any profile`() = runTest {
        val profileId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true

        val result = controller.undismiss(auth, profileId, UUID.random(), null)

        assertTrue(result)
    }

    // --- feedback routing ---

    @Test
    fun `feedback BOOST upserts a high rating`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true

        assertTrue(controller.feedback(auth, profileId, RecommendationFeedback.BOOST, metadataId, 3, null))

        coVerify { ratingService.updateRating(profileId, 5, metadataId, 3, null) }
        coVerify(exactly = 0) { recommendationService.dismiss(any(), any(), any()) }
    }

    @Test
    fun `feedback LOWER upserts a low rating`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true

        coEvery { metadataService.getById(metadataId) } returns mockk { every { version } returns 7 }

        controller.feedback(auth, profileId, RecommendationFeedback.LOWER, metadataId, null, null)

        coVerify { ratingService.updateRating(profileId, 2, metadataId, 7, null) }
    }

    @Test
    fun `feedback HIDE dismisses and does not rate`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true

        assertTrue(controller.feedback(auth, profileId, RecommendationFeedback.HIDE, metadataId, null, null))

        coVerify { recommendationService.dismiss(profileId, metadataId, null) }
        coVerify(exactly = 0) { ratingService.updateRating(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `feedback rejects when principal does not own profile`() = runTest {
        val principalId = UUID.random()
        val otherPrincipalId = UUID.random()
        val profileId = UUID.random()
        val auth = authenticatedContext(principalId)
        every { groupEvaluator.hasAdminGroup(auth) } returns false
        every { groupEvaluator.hasSaGroup(auth) } returns false
        coEvery { profileService.getById(profileId) } returns profileOwnedBy(profileId, otherPrincipalId)

        assertFailsWith<SecurityException> {
            controller.feedback(auth, profileId, RecommendationFeedback.BOOST, UUID.random(), null, null)
        }
        coVerify(exactly = 0) { ratingService.updateRating(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `feedback CARE upserts a mid rating`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true

        coEvery { metadataService.getById(metadataId) } returns mockk { every { version } returns 7 }

        controller.feedback(auth, profileId, RecommendationFeedback.CARE, metadataId, null, null)

        coVerify { ratingService.updateRating(profileId, 4, metadataId, 7, null) }
    }

    @Test
    fun `feedback with an omitted version rejects missing metadata before saving`() = runTest {
        val metadataId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        coEvery { metadataService.getById(metadataId) } returns null
        assertFailsWith<IllegalArgumentException> {
            controller.feedback(auth, UUID.random(), RecommendationFeedback.BOOST, metadataId, null, null)
        }
        coVerify(exactly = 0) { ratingService.updateRating(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `collection feedback does not resolve a metadata version`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val auth = authenticatedContext()
        every { groupEvaluator.hasAdminGroup(auth) } returns true
        controller.feedback(auth, profileId, RecommendationFeedback.CARE, null, null, collectionId)
        coVerify { ratingService.updateRating(profileId, 4, null, null, collectionId) }
        coVerify(exactly = 0) { metadataService.getById(any()) }
    }

    @Test
    fun `strategies and placements expose their sub-controllers`() {
        assertEquals(RecommendationStrategiesMutation, controller.strategies())
        assertEquals(RecommendationPlacementsMutation, controller.placements())
        assertEquals(RecommendationContextsMutation, controller.contexts())
        assertEquals(PersonalizationSignalsMutation, controller.personalizationSignals())
    }
}
