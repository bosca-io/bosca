@file:OptIn(ExperimentalUuidApi::class)

package bosca.recommendations.model

import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

class RecommendationDismissalTest {

    @Test
    fun `has sensible defaults`() {
        val profileId = UUID.random()
        val dismissal = RecommendationDismissal(profileId = profileId)
        assertEquals(UUID.NIL, dismissal.id)
        assertEquals(profileId, dismissal.profileId)
        assertNull(dismissal.metadataId)
        assertNull(dismissal.collectionId)
    }

    @Test
    fun `stores all fields`() {
        val id = UUID.random()
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val collectionId = UUID.random()
        val dismissal = RecommendationDismissal(
            id = id,
            profileId = profileId,
            metadataId = metadataId,
            collectionId = collectionId,
        )
        assertEquals(id, dismissal.id)
        assertEquals(profileId, dismissal.profileId)
        assertEquals(metadataId, dismissal.metadataId)
        assertEquals(collectionId, dismissal.collectionId)
    }

    @Test
    fun `equality`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val a = RecommendationDismissal(profileId = profileId, metadataId = metadataId)
        val b = RecommendationDismissal(profileId = profileId, metadataId = metadataId)
        assertEquals(a.profileId, b.profileId)
        assertEquals(a.metadataId, b.metadataId)
    }

    @Test
    fun `stores metadata dismissal without collection`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val dismissal = RecommendationDismissal(
            profileId = profileId,
            metadataId = metadataId,
        )
        assertEquals(metadataId, dismissal.metadataId)
        assertNull(dismissal.collectionId)
    }

    @Test
    fun `stores collection dismissal without metadata`() {
        val profileId = UUID.random()
        val collectionId = UUID.random()
        val dismissal = RecommendationDismissal(
            profileId = profileId,
            collectionId = collectionId,
        )
        assertNull(dismissal.metadataId)
        assertEquals(collectionId, dismissal.collectionId)
    }
}
