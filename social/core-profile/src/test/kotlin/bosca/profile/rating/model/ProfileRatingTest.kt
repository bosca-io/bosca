package bosca.profile.rating.model

import bosca.serialization.UUID
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProfileRatingTest {

    // -- ProfileRating --

    @Test
    fun `ProfileRating creation with required fields`() {
        val profileId = UUID.random()
        val rating = ProfileRating(profileId = profileId, rating = 5)
        assertEquals(profileId, rating.profileId)
        assertEquals(5, rating.rating)
    }

    @Test
    fun `ProfileRating id defaults to zero`() {
        val rating = ProfileRating(profileId = UUID.random(), rating = 3)
        assertEquals(0L, rating.id)
    }

    @Test
    fun `ProfileRating optional fields default to null`() {
        val rating = ProfileRating(profileId = UUID.random(), rating = 1)
        assertNull(rating.collectionId)
        assertNull(rating.metadataId)
        assertNull(rating.metadataVersion)
    }

    @Test
    fun `ProfileRating created has a default value`() {
        val rating = ProfileRating(profileId = UUID.random(), rating = 4)
        assertNotNull(rating.created)
    }

    @Test
    fun `ProfileRating creation with all fields`() {
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val metadataId = UUID.random()
        val rating = ProfileRating(
            id = 10L,
            profileId = profileId,
            collectionId = collectionId,
            metadataId = metadataId,
            metadataVersion = 2,
            rating = 4
        )
        assertEquals(10L, rating.id)
        assertEquals(profileId, rating.profileId)
        assertEquals(collectionId, rating.collectionId)
        assertEquals(metadataId, rating.metadataId)
        assertEquals(2, rating.metadataVersion)
        assertEquals(4, rating.rating)
    }

    @Test
    fun `ProfileRating data class equality`() {
        val profileId = UUID.random()
        val now = OffsetDateTime.now()
        val a = ProfileRating(id = 1, profileId = profileId, rating = 5, created = now)
        val b = ProfileRating(id = 1, profileId = profileId, rating = 5, created = now)
        assertEquals(a, b)
    }

    // -- ProfileRatingInput --

    @Test
    fun `ProfileRatingInput creation with rating only`() {
        val input = ProfileRatingInput(rating = 3)
        assertEquals(3, input.rating)
        assertNull(input.metadataId)
        assertNull(input.metadataVersion)
        assertNull(input.collectionId)
    }

    @Test
    fun `ProfileRatingInput creation with metadata fields`() {
        val metadataId = UUID.random()
        val input = ProfileRatingInput(
            rating = 5,
            metadataId = metadataId,
            metadataVersion = 1
        )
        assertEquals(5, input.rating)
        assertEquals(metadataId, input.metadataId)
        assertEquals(1, input.metadataVersion)
        assertNull(input.collectionId)
    }

    @Test
    fun `ProfileRatingInput creation with collection field`() {
        val collectionId = UUID.random()
        val input = ProfileRatingInput(
            rating = 2,
            collectionId = collectionId
        )
        assertEquals(2, input.rating)
        assertEquals(collectionId, input.collectionId)
        assertNull(input.metadataId)
    }

    @Test
    fun `ProfileRatingInput data class equality`() {
        val a = ProfileRatingInput(rating = 4)
        val b = ProfileRatingInput(rating = 4)
        assertEquals(a, b)
    }
}
