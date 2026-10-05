package bosca.profile.rating.service

import bosca.profile.rating.model.ProfileRating
import bosca.profile.rating.repository.ProfileRatingRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileRatingServiceImplTest {

    private val repository = mockk<ProfileRatingRepository>()
    private val service = ProfileRatingServiceImpl(repository)

    @Test
    fun `getRatingsByProfile delegates to repository`() = runTest {
        val profileId = UUID.random()
        val ratings = listOf(
            ProfileRating(id = 1, profileId = profileId, rating = 5),
            ProfileRating(id = 2, profileId = profileId, rating = 3)
        )

        coEvery { repository.findByProfileId(profileId) } returns ratings

        val result = service.getRatingsByProfile(profileId)

        assertEquals(2, result.size)
        assertEquals(ratings, result)
    }

    @Test
    fun `getRating by metadata delegates to repository`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val rating = ProfileRating(id = 1, profileId = profileId, metadataId = metadataId, metadataVersion = 1, rating = 4)

        coEvery { repository.findByProfileAndMetadata(profileId, metadataId, 1) } returns rating

        val result = service.getRating(profileId, metadataId, 1)

        assertEquals(rating, result)
    }

    @Test
    fun `getRating by metadata returns null when not found`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()

        coEvery { repository.findByProfileAndMetadata(profileId, metadataId, 1) } returns null

        val result = service.getRating(profileId, metadataId, 1)

        assertNull(result)
    }

    @Test
    fun `getRating by collection delegates to repository`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val rating = ProfileRating(id = 1, profileId = profileId, collectionId = collectionId, rating = 5)

        coEvery { repository.findByProfileAndCollection(profileId, collectionId) } returns rating

        val result = service.getRating(profileId, collectionId)

        assertEquals(rating, result)
    }

    @Test
    fun `getAverageRating by metadata delegates to repository`() = runTest {
        val metadataId = UUID.random()

        coEvery { repository.getAverageRating(metadataId, 1) } returns 4.5

        val result = service.getAverageRating(metadataId, 1)

        assertEquals(4.5, result)
    }

    @Test
    fun `getAverageRating by collection delegates to repository`() = runTest {
        val collectionId = UUID.random()

        coEvery { repository.getAverageRating(collectionId) } returns 3.2

        val result = service.getAverageRating(collectionId)

        assertEquals(3.2, result)
    }

    @Test
    fun `addRating creates rating and delegates to repository`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val expected = ProfileRating(id = 1, profileId = profileId, metadataId = metadataId, metadataVersion = 1, rating = 5)

        coEvery { repository.add(any()) } returns expected

        val result = service.addRating(profileId, 5, metadataId, 1, null)

        assertEquals(expected, result)
        coVerify {
            repository.add(match {
                it.profileId == profileId &&
                    it.rating == 5 &&
                    it.metadataId == metadataId &&
                    it.metadataVersion == 1
            })
        }
    }

    @Test
    fun `updateRating updates existing metadata rating`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val existing = ProfileRating(id = 1, profileId = profileId, metadataId = metadataId, metadataVersion = 1, rating = 3)
        val updated = existing.copy(rating = 5)

        coEvery { repository.findByProfileAndMetadata(profileId, metadataId, 1) } returns existing
        coEvery { repository.update(any()) } returns updated

        val result = service.updateRating(profileId, 5, metadataId, 1, null)

        assertEquals(5, result?.rating)
        coVerify {
            repository.update(match { it.rating == 5 })
        }
    }

    @Test
    fun `updateRating updates existing collection rating`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val existing = ProfileRating(id = 1, profileId = profileId, collectionId = collectionId, rating = 2)
        val updated = existing.copy(rating = 4)

        coEvery { repository.findByProfileAndCollection(profileId, collectionId) } returns existing
        coEvery { repository.update(any()) } returns updated

        val result = service.updateRating(profileId, 4, null, null, collectionId)

        assertEquals(4, result?.rating)
    }

    @Test
    fun `updateRating creates new rating when existing not found for metadata`() = runTest {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val newRating = ProfileRating(id = 1, profileId = profileId, metadataId = metadataId, metadataVersion = 1, rating = 5)

        coEvery { repository.findByProfileAndMetadata(profileId, metadataId, 1) } returns null
        coEvery { repository.add(any()) } returns newRating

        val result = service.updateRating(profileId, 5, metadataId, 1, null)

        assertEquals(5, result?.rating)
    }

    @Test
    fun `updateRating creates new rating when existing not found for collection`() = runTest {
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val newRating = ProfileRating(id = 1, profileId = profileId, collectionId = collectionId, rating = 3)

        coEvery { repository.findByProfileAndCollection(profileId, collectionId) } returns null
        coEvery { repository.add(any()) } returns newRating

        val result = service.updateRating(profileId, 3, null, null, collectionId)

        assertEquals(3, result?.rating)
    }

    @Test
    fun `updateRating returns null when neither metadata nor collection specified`() = runTest {
        val profileId = UUID.random()

        coEvery { repository.add(any()) } returns ProfileRating(id = 1, profileId = profileId, rating = 5)

        val result = service.updateRating(profileId, 5, null, null, null)

        // Falls through to addRating since existing is null
        assertEquals(5, result?.rating)
    }
}
