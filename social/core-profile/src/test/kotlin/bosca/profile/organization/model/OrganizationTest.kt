package bosca.profile.organization.model

import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrganizationTest {

    private val attrs = JsonObject(mapOf("key" to JsonPrimitive("value")))
    private val sysAttrs = JsonObject(mapOf("sys" to JsonPrimitive("data")))

    @Test
    fun `Organization creation preserves all constructor fields`() {
        val id = UUID.random()
        val profileId = UUID.random()
        val org = Organization(
            id = id,
            name = "Test Organization",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC,
            profileId = profileId
        )
        assertEquals(id, org.id)
        assertEquals("Test Organization", org.name)
        assertEquals(attrs, org.attributes)
        assertEquals(sysAttrs, org.systemAttributes)
        assertEquals(ProfileVisibility.PUBLIC, org.visibility)
        assertEquals(profileId, org.profileId)
    }

    @Test
    fun `Organization id defaults to NIL`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER,
            profileId = UUID.random()
        )
        assertEquals(UUID.NIL, org.id)
    }

    @Test
    fun `Organization created has default value`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER,
            profileId = UUID.random()
        )
        assertNotNull(org.created)
    }

    @Test
    fun `Organization modified has default value`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER,
            profileId = UUID.random()
        )
        assertNotNull(org.modified)
    }

    @Test
    fun `Organization public is true when visibility is PUBLIC`() {
        val org = Organization(
            name = "Public Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC,
            profileId = UUID.random()
        )
        assertTrue(org.public)
    }

    @Test
    fun `Organization public is false when visibility is not PUBLIC`() {
        val nonPublicVisibilities = listOf(
            ProfileVisibility.SYSTEM,
            ProfileVisibility.USER,
            ProfileVisibility.FRIENDS,
            ProfileVisibility.FRIENDS_OF_FRIENDS
        )
        nonPublicVisibilities.forEach { visibility ->
            val org = Organization(
                name = "Non-public",
                attributes = attrs,
                systemAttributes = sysAttrs,
                visibility = visibility,
                profileId = UUID.random()
            )
            assertFalse(org.public, "Expected public=false for visibility=$visibility")
        }
    }

    @Test
    fun `Organization version is always null`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC,
            profileId = UUID.random()
        )
        assertNull(org.version)
    }

    @Test
    fun `Organization languageTag is always null`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC,
            profileId = UUID.random()
        )
        assertNull(org.languageTag)
    }

    @Test
    fun `Organization itemAttributes is always null`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC,
            profileId = UUID.random()
        )
        assertNull(org.itemAttributes)
    }

    @Test
    fun `Organization workflowStateId is always published`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER,
            profileId = UUID.random()
        )
        assertEquals("published", org.workflowStateId)
    }

    @Test
    fun `Organization workflowStatePendingId is always null`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER,
            profileId = UUID.random()
        )
        assertNull(org.workflowStatePendingId)
    }

    @Test
    fun `Organization ready is always null`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER,
            profileId = UUID.random()
        )
        assertNull(org.ready)
    }

    @Test
    fun `Organization publicContent is always false`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC,
            profileId = UUID.random()
        )
        assertFalse(org.publicContent)
    }

    @Test
    fun `Organization publicList is always false`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC,
            profileId = UUID.random()
        )
        assertFalse(org.publicList)
    }

    @Test
    fun `Organization publicSupplementary is always false`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC,
            profileId = UUID.random()
        )
        assertFalse(org.publicSupplementary)
    }

    @Test
    fun `Organization isPublished is always true`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER,
            profileId = UUID.random()
        )
        assertTrue(org.isPublished)
    }

    @Test
    fun `Organization isAdvertised is always false`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER,
            profileId = UUID.random()
        )
        assertFalse(org.isAdvertised)
    }

    @Test
    fun `Organization isDeleted is always false`() {
        val org = Organization(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER,
            profileId = UUID.random()
        )
        assertFalse(org.isDeleted)
    }

    @Test
    fun `Organization data class equality`() {
        val id = UUID.random()
        val profileId = UUID.random()
        val created = java.time.OffsetDateTime.now()
        val modified = created
        val a = Organization(
            id = id,
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC,
            profileId = profileId,
            created = created,
            modified = modified
        )
        val b = Organization(
            id = id,
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC,
            profileId = profileId,
            created = created,
            modified = modified
        )
        assertEquals(a, b)
    }
}
