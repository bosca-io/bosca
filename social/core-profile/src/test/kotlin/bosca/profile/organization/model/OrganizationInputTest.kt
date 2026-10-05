package bosca.profile.organization.model

import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class OrganizationInputTest {

    private val attrs = JsonObject(mapOf("key" to JsonPrimitive("value")))
    private val sysAttrs = JsonObject(mapOf("sys" to JsonPrimitive("data")))

    @Test
    fun `toOrganization creates Organization with correct fields`() {
        val id = UUID.random()
        val profileId = UUID.random()
        val input = OrganizationInput(
            id = id,
            profile = profileId,
            name = "Test Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC
        )
        val org = input.toOrganization()
        assertEquals(id, org.id)
        assertEquals("Test Org", org.name)
        assertEquals(attrs, org.attributes)
        assertEquals(sysAttrs, org.systemAttributes)
        assertEquals(ProfileVisibility.PUBLIC, org.visibility)
        assertEquals(profileId, org.profileId)
    }

    @Test
    fun `toOrganization with profile override sets the provided profile`() {
        val input = OrganizationInput(
            name = "Test Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.FRIENDS
        )
        val overrideProfile = UUID.random()
        val org = input.toOrganization(overrideProfile)
        assertEquals(overrideProfile, org.profileId)
    }

    @Test
    fun `OrganizationInput defaults have correct values`() {
        val input = OrganizationInput(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER
        )
        assertEquals(UUID.NIL, input.id)
        assertEquals(UUID.NIL, input.profile)
        assertEquals(null, input.slug)
        assertEquals(emptyList(), input.profileAttributes)
        assertEquals(emptyList(), input.domains)
        assertEquals(emptyList(), input.signupEmails)
        assertEquals(emptyList(), input.signupTokens)
    }
}
