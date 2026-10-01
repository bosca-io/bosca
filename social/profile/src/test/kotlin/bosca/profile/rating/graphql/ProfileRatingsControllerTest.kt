package bosca.profile.rating.graphql

import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.rating.model.ProfileRating
import bosca.profile.rating.service.ProfileRatingService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileRatingsControllerTest {

    private val service = mockk<ProfileRatingService>()
    private val permissionEvaluator = mockk<ProfilePermissionEvaluator>()
    private val controller = ProfileRatingsController(service, permissionEvaluator)
    private val auth = mockk<AuthenticationContext>(relaxed = true)

    init {
        coJustRun { permissionEvaluator.verifyAllowed(any(), any<Profile>(), any()) }
    }

    private fun createProfile(): Profile {
        return Profile(
            id = UUID.random(),
            type = ProfileType.GENERIC,
            name = "Test",
            visibility = ProfileVisibility.PUBLIC
        )
    }

    @Test
    fun `ratings returns ratings with default limit and offset`() = runTest {
        val profile = createProfile()
        val ratings = (1..15).map {
            ProfileRating(id = it.toLong(), profileId = profile.id, rating = it % 5 + 1)
        }

        coEvery { service.getRatingsByProfile(profile.id) } returns ratings

        val result = controller.ratings(auth, profile)

        assertEquals(10, result.size)
    }

    @Test
    fun `ratings applies limit and offset`() = runTest {
        val profile = createProfile()
        val ratings = (1..20).map {
            ProfileRating(id = it.toLong(), profileId = profile.id, rating = it % 5 + 1)
        }

        coEvery { service.getRatingsByProfile(profile.id) } returns ratings

        val result = controller.ratings(auth, profile, limit = 5, offset = 3)

        assertEquals(5, result.size)
    }

    @Test
    fun `ratings returns empty list when no ratings`() = runTest {
        val profile = createProfile()

        coEvery { service.getRatingsByProfile(profile.id) } returns emptyList()

        val result = controller.ratings(auth, profile)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `count returns total number of ratings`() = runTest {
        val profile = createProfile()
        val ratings = listOf(
            ProfileRating(id = 1, profileId = profile.id, rating = 5),
            ProfileRating(id = 2, profileId = profile.id, rating = 3)
        )

        coEvery { service.getRatingsByProfile(profile.id) } returns ratings

        val result = controller.count(auth, profile)

        assertEquals(2L, result)
    }

    @Test
    fun `count returns 0 when no ratings`() = runTest {
        val profile = createProfile()

        coEvery { service.getRatingsByProfile(profile.id) } returns emptyList()

        val result = controller.count(auth, profile)

        assertEquals(0L, result)
    }

    @Test
    fun `rating by metadata returns rating when found`() = runTest {
        val profile = createProfile()
        val metadataId = UUID.random()
        val expected = ProfileRating(id = 1, profileId = profile.id, metadataId = metadataId, metadataVersion = 1, rating = 4)

        coEvery { service.getRating(profile.id, metadataId, 1) } returns expected

        val result = controller.rating(auth, profile, metadataId = metadataId, metadataVersion = 1)

        assertEquals(expected, result)
    }

    @Test
    fun `rating by collection returns rating when found`() = runTest {
        val profile = createProfile()
        val collectionId = UUID.random()
        val expected = ProfileRating(id = 1, profileId = profile.id, collectionId = collectionId, rating = 5)

        coEvery { service.getRating(profile.id, collectionId) } returns expected

        val result = controller.rating(auth, profile, collectionId = collectionId)

        assertEquals(expected, result)
    }

    @Test
    fun `rating returns null when neither metadata nor collection specified`() = runTest {
        val profile = createProfile()

        val result = controller.rating(auth, profile)

        assertNull(result)
    }

    @Test
    fun `rating returns null when metadata is provided without version`() = runTest {
        val profile = createProfile()

        val result = controller.rating(auth, profile, metadataId = UUID.random())

        assertNull(result)
    }
}
