package bosca.profile.mark.model

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProfileMarkTest {

    @Test
    fun `ProfileMark creation with required profileId`() {
        val profileId = UUID.random()
        val mark = ProfileMark(profileId = profileId)
        assertEquals(profileId, mark.profileId)
    }

    @Test
    fun `ProfileMark id defaults to zero`() {
        val mark = ProfileMark(profileId = UUID.random())
        assertEquals(0L, mark.id)
    }

    @Test
    fun `ProfileMark optional fields default to null`() {
        val mark = ProfileMark(profileId = UUID.random())
        assertNull(mark.metadataId)
        assertNull(mark.metadataVersion)
        assertNull(mark.collectionId)
        assertNull(mark.attributes)
    }

    @Test
    fun `ProfileMark created has a default value`() {
        val mark = ProfileMark(profileId = UUID.random())
        assertNotNull(mark.created)
    }

    @Test
    fun `ProfileMark creation with all fields`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val collectionId = UUID.random()
        val attrs = JsonObject(mapOf("highlight" to JsonPrimitive("yellow")))
        val mark = ProfileMark(
            id = 55L,
            profileId = profileId,
            metadataId = metadataId,
            metadataVersion = 2,
            collectionId = collectionId,
            attributes = attrs
        )
        assertEquals(55L, mark.id)
        assertEquals(profileId, mark.profileId)
        assertEquals(metadataId, mark.metadataId)
        assertEquals(2, mark.metadataVersion)
        assertEquals(collectionId, mark.collectionId)
        assertEquals(attrs, mark.attributes)
    }

    @Test
    fun `ProfileMark data class equality`() {
        val profileId = UUID.random()
        val metadataId = UUID.random()
        val now = OffsetDateTime.now()
        val a = ProfileMark(id = 1, profileId = profileId, metadataId = metadataId, metadataVersion = 1, created = now)
        val b = ProfileMark(id = 1, profileId = profileId, metadataId = metadataId, metadataVersion = 1, created = now)
        assertEquals(a, b)
    }

    @Test
    fun `ProfileMark with metadata mark`() {
        val mark = ProfileMark(
            profileId = UUID.random(),
            metadataId = UUID.random(),
            metadataVersion = 3
        )
        assertNotNull(mark.metadataId)
        assertEquals(3, mark.metadataVersion)
        assertNull(mark.collectionId)
    }

    @Test
    fun `ProfileMark with collection mark`() {
        val mark = ProfileMark(
            profileId = UUID.random(),
            collectionId = UUID.random()
        )
        assertNull(mark.metadataId)
        assertNotNull(mark.collectionId)
    }

    @Test
    fun `ProfileMark copy changes specific fields`() {
        val original = ProfileMark(
            profileId = UUID.random(),
            metadataId = UUID.random(),
            metadataVersion = 1
        )
        val copy = original.copy(metadataVersion = 2)
        assertEquals(2, copy.metadataVersion)
        assertEquals(original.profileId, copy.profileId)
        assertEquals(original.metadataId, copy.metadataId)
    }

    @Test
    fun `ProfileMark hashCode is consistent for equal instances`() {
        val profileId = UUID.random()
        val now = OffsetDateTime.now()
        val a = ProfileMark(id = 10, profileId = profileId, created = now)
        val b = ProfileMark(id = 10, profileId = profileId, created = now)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
