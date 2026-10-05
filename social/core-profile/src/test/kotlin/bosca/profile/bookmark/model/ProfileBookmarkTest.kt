package bosca.profile.bookmark.model

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProfileBookmarkTest {

    @Test
    fun `ProfileBookmark creation with required profileId`() {
        val profileId = UUID.random()
        val bookmark = ProfileBookmark(profileId = profileId)
        assertEquals(profileId, bookmark.profileId)
    }

    @Test
    fun `ProfileBookmark id defaults to zero`() {
        val bookmark = ProfileBookmark(profileId = UUID.random())
        assertEquals(0L, bookmark.id)
    }

    @Test
    fun `ProfileBookmark optional fields default to null`() {
        val bookmark = ProfileBookmark(profileId = UUID.random())
        assertNull(bookmark.metadataId)
        assertNull(bookmark.metadataVersion)
        assertNull(bookmark.collectionId)
        assertNull(bookmark.attributes)
    }

    @Test
    fun `ProfileBookmark created has a default value`() {
        val bookmark = ProfileBookmark(profileId = UUID.random())
        assertNotNull(bookmark.created)
    }

    @Test
    fun `ProfileBookmark creation with all fields`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val collectionId = UUID.random()
        val attrs = JsonPrimitive("test")
        val bookmark = ProfileBookmark(
            id = 42L,
            profileId = profileId,
            metadataId = metadataId,
            metadataVersion = 3,
            collectionId = collectionId,
            attributes = attrs
        )
        assertEquals(42L, bookmark.id)
        assertEquals(profileId, bookmark.profileId)
        assertEquals(metadataId, bookmark.metadataId)
        assertEquals(3, bookmark.metadataVersion)
        assertEquals(collectionId, bookmark.collectionId)
        assertEquals(attrs, bookmark.attributes)
    }

    @Test
    fun `ProfileBookmark data class equality`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val now = OffsetDateTime.now()
        val a = ProfileBookmark(id = 1, profileId = profileId, metadataId = metadataId, metadataVersion = 1, created = now)
        val b = ProfileBookmark(id = 1, profileId = profileId, metadataId = metadataId, metadataVersion = 1, created = now)
        assertEquals(a, b)
    }

    @Test
    fun `ProfileBookmark with metadata bookmark`() {
        val bookmark = ProfileBookmark(
            profileId = UUID.random(),
            metadataId = UUID.random(),
            metadataVersion = 5
        )
        assertNotNull(bookmark.metadataId)
        assertEquals(5, bookmark.metadataVersion)
        assertNull(bookmark.collectionId)
    }

    @Test
    fun `ProfileBookmark with collection bookmark`() {
        val bookmark = ProfileBookmark(
            profileId = UUID.random(),
            collectionId = UUID.random()
        )
        assertNull(bookmark.metadataId)
        assertNotNull(bookmark.collectionId)
    }
}
