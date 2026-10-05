package bosca.profile.rating.graphql

import bosca.profile.rating.model.ProfileRating
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileRatingControllerTest {

    private val controller = ProfileRatingController()

    @Test
    fun `id returns rating id`() {
        val rating = ProfileRating(id = 42, profileId = UUID.random(), rating = 5)
        assertEquals(42L, controller.id(rating))
    }

    @Test
    fun `rating returns rating value`() {
        val rating = ProfileRating(id = 1, profileId = UUID.random(), rating = 3)
        assertEquals(3, controller.rating(rating))
    }

    @Test
    fun `created returns rating created timestamp`() {
        val rating = ProfileRating(id = 1, profileId = UUID.random(), rating = 5)
        assertEquals(rating.created, controller.created(rating))
    }

    @Test
    fun `id handles zero id`() {
        val rating = ProfileRating(profileId = UUID.random(), rating = 1)
        assertEquals(0L, controller.id(rating))
    }

    @Test
    fun `rating returns various rating values`() {
        for (ratingValue in 1..5) {
            val rating = ProfileRating(id = 1, profileId = UUID.random(), rating = ratingValue)
            assertEquals(ratingValue, controller.rating(rating))
        }
    }
}
