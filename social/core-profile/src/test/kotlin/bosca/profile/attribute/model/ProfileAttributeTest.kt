package bosca.profile.attribute.model

import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileAttributeTest {

    private val profileId = UUID.random()

    @Test
    fun `getAttributeString returns value for existing key`() {
        val attributes = JsonObject(mapOf("name" to JsonPrimitive("John"), "email" to JsonPrimitive("john@test.com")))
        val attr = ProfileAttribute(
            profile = profileId,
            typeId = "contact",
            visibility = ProfileVisibility.USER,
            confidence = 100,
            priority = 1,
            source = "manual",
            attributes = attributes
        )
        assertEquals("John", attr.getAttributeString("name"))
    }

    @Test
    fun `getAttributeString returns null for missing key`() {
        val attributes = JsonObject(mapOf("name" to JsonPrimitive("John")))
        val attr = ProfileAttribute(
            profile = profileId,
            typeId = "contact",
            visibility = ProfileVisibility.USER,
            confidence = 100,
            priority = 1,
            source = "manual",
            attributes = attributes
        )
        assertNull(attr.getAttributeString("missing"))
    }

    @Test
    fun `getAttributeString returns null when attributes is null`() {
        val attr = ProfileAttribute(
            profile = profileId,
            typeId = "contact",
            visibility = ProfileVisibility.USER,
            confidence = 100,
            priority = 1,
            source = "manual",
            attributes = null
        )
        assertNull(attr.getAttributeString("name"))
    }

    @Test
    fun `List getAttributeString finds attribute by typeId and name`() {
        val attrs1 = JsonObject(mapOf("name" to JsonPrimitive("John")))
        val attrs2 = JsonObject(mapOf("name" to JsonPrimitive("Jane")))
        val list = listOf(
            ProfileAttribute(profile = profileId, typeId = "contact", visibility = ProfileVisibility.USER, confidence = 100, priority = 1, source = "manual", attributes = attrs1),
            ProfileAttribute(profile = profileId, typeId = "work", visibility = ProfileVisibility.USER, confidence = 100, priority = 1, source = "manual", attributes = attrs2)
        )
        assertEquals("John", list.getAttributeString("contact", "name"))
        assertEquals("Jane", list.getAttributeString("work", "name"))
    }

    @Test
    fun `List getAttributeString returns null for non-existent typeId`() {
        val attrs = JsonObject(mapOf("name" to JsonPrimitive("John")))
        val list = listOf(
            ProfileAttribute(profile = profileId, typeId = "contact", visibility = ProfileVisibility.USER, confidence = 100, priority = 1, source = "manual", attributes = attrs)
        )
        assertNull(list.getAttributeString("nonexistent", "name"))
    }

    @Test
    fun `List getAttributeString returns null for empty list`() {
        val list = emptyList<ProfileAttribute>()
        assertNull(list.getAttributeString("contact", "name"))
    }

    @Test
    fun `ProfileAttribute default values are correct`() {
        val attr = ProfileAttribute(
            profile = profileId,
            typeId = "test",
            visibility = ProfileVisibility.USER,
            confidence = 50,
            priority = 0,
            source = "system"
        )
        assertEquals(UUID.NIL, attr.id)
        assertNull(attr.attributes)
        assertNull(attr.metadataId)
        assertNull(attr.expires)
    }
}
