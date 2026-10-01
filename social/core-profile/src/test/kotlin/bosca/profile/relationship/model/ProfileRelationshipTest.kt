package bosca.profile.relationship.model

import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileRelationshipTest {

    @Test
    fun `ProfileRelationship creation with required fields`() {
        val profileId1 = UUID.random()
        val profileId2 = UUID.random()
        val relationship = ProfileRelationship(
            profileId1 = profileId1,
            profileId2 = profileId2,
            type = "friend"
        )
        assertEquals(profileId1, relationship.profileId1)
        assertEquals(profileId2, relationship.profileId2)
        assertEquals("friend", relationship.type)
    }

    @Test
    fun `ProfileRelationship attributes defaults to null`() {
        val relationship = ProfileRelationship(
            profileId1 = UUID.random(),
            profileId2 = UUID.random(),
            type = "follower"
        )
        assertNull(relationship.attributes)
    }

    @Test
    fun `ProfileRelationship creation with attributes`() {
        val attrs = JsonObject(mapOf("since" to JsonPrimitive("2024-01-01"), "mutual" to JsonPrimitive(true)))
        val relationship = ProfileRelationship(
            profileId1 = UUID.random(),
            profileId2 = UUID.random(),
            type = "friend",
            attributes = attrs
        )
        assertEquals(attrs, relationship.attributes)
    }

    @Test
    fun `ProfileRelationship data class equality`() {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val a = ProfileRelationship(profileId1 = id1, profileId2 = id2, type = "friend")
        val b = ProfileRelationship(profileId1 = id1, profileId2 = id2, type = "friend")
        assertEquals(a, b)
    }

    @Test
    fun `ProfileRelationship with different types are not equal`() {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val a = ProfileRelationship(profileId1 = id1, profileId2 = id2, type = "friend")
        val b = ProfileRelationship(profileId1 = id1, profileId2 = id2, type = "follower")
        assertNotEquals(a, b)
    }

    @Test
    fun `ProfileRelationship with swapped profile IDs are not equal`() {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val a = ProfileRelationship(profileId1 = id1, profileId2 = id2, type = "friend")
        val b = ProfileRelationship(profileId1 = id2, profileId2 = id1, type = "friend")
        assertNotEquals(a, b)
    }

    @Test
    fun `ProfileRelationship copy changes type`() {
        val original = ProfileRelationship(
            profileId1 = UUID.random(),
            profileId2 = UUID.random(),
            type = "friend"
        )
        val copy = original.copy(type = "blocked")
        assertEquals("blocked", copy.type)
        assertEquals(original.profileId1, copy.profileId1)
        assertEquals(original.profileId2, copy.profileId2)
    }

    @Test
    fun `ProfileRelationship hashCode is consistent for equal instances`() {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val a = ProfileRelationship(profileId1 = id1, profileId2 = id2, type = "t")
        val b = ProfileRelationship(profileId1 = id1, profileId2 = id2, type = "t")
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ProfileRelationship toString contains type`() {
        val relationship = ProfileRelationship(
            profileId1 = UUID.random(),
            profileId2 = UUID.random(),
            type = "mentor"
        )
        assertTrue(relationship.toString().contains("mentor"))
    }

    @Test
    fun `ProfileRelationship with null and non-null attributes are not equal`() {
        val id1 = UUID.random()
        val id2 = UUID.random()
        val a = ProfileRelationship(profileId1 = id1, profileId2 = id2, type = "friend", attributes = null)
        val b = ProfileRelationship(
            profileId1 = id1,
            profileId2 = id2,
            type = "friend",
            attributes = JsonObject(emptyMap())
        )
        assertNotEquals(a, b)
    }
}
