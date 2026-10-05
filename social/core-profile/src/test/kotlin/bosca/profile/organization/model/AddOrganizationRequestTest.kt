package bosca.profile.organization.model

import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.model.ProfileInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AddOrganizationRequestTest {

    private val attrs = JsonObject(mapOf("key" to JsonPrimitive("value")))
    private val sysAttrs = JsonObject(mapOf("sys" to JsonPrimitive("data")))

    @Test
    fun `AddOrganizationRequest creation preserves organization and profile`() {
        val orgInput = OrganizationInput(
            name = "Test Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC
        )
        val profileInput = ProfileInput(
            name = "John Doe",
            visibility = ProfileVisibility.USER
        )
        val request = AddOrganizationRequest(
            organization = orgInput,
            profile = profileInput
        )
        assertEquals(orgInput, request.organization)
        assertEquals(profileInput, request.profile)
    }

    @Test
    fun `AddOrganizationRequest organization fields are accessible`() {
        val orgInput = OrganizationInput(
            name = "My Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.FRIENDS
        )
        val profileInput = ProfileInput(
            name = "Jane",
            visibility = ProfileVisibility.FRIENDS
        )
        val request = AddOrganizationRequest(organization = orgInput, profile = profileInput)
        assertEquals("My Org", request.organization.name)
        assertEquals(ProfileVisibility.FRIENDS, request.organization.visibility)
    }

    @Test
    fun `AddOrganizationRequest profile fields are accessible`() {
        val orgInput = OrganizationInput(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.PUBLIC
        )
        val profileInput = ProfileInput(
            slug = "jane-doe",
            name = "Jane Doe",
            visibility = ProfileVisibility.PUBLIC
        )
        val request = AddOrganizationRequest(organization = orgInput, profile = profileInput)
        assertEquals("jane-doe", request.profile.slug)
        assertEquals("Jane Doe", request.profile.name)
    }

    @Test
    fun `AddOrganizationRequest data class equality`() {
        val orgInput = OrganizationInput(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER
        )
        val profileInput = ProfileInput(name = "Name", visibility = ProfileVisibility.USER)
        val a = AddOrganizationRequest(organization = orgInput, profile = profileInput)
        val b = AddOrganizationRequest(organization = orgInput, profile = profileInput)
        assertEquals(a, b)
    }

    @Test
    fun `AddOrganizationRequest hashCode is consistent for equal instances`() {
        val orgInput = OrganizationInput(
            name = "Org",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER
        )
        val profileInput = ProfileInput(name = "Name", visibility = ProfileVisibility.USER)
        val a = AddOrganizationRequest(organization = orgInput, profile = profileInput)
        val b = AddOrganizationRequest(organization = orgInput, profile = profileInput)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `AddOrganizationRequest copy changes organization`() {
        val orgInput = OrganizationInput(
            name = "Original",
            attributes = attrs,
            systemAttributes = sysAttrs,
            visibility = ProfileVisibility.USER
        )
        val profileInput = ProfileInput(name = "Profile", visibility = ProfileVisibility.USER)
        val original = AddOrganizationRequest(organization = orgInput, profile = profileInput)
        val newOrg = orgInput.copy(name = "Updated")
        val copy = original.copy(organization = newOrg)
        assertEquals("Updated", copy.organization.name)
        assertEquals(original.profile, copy.profile)
    }
}
