package bosca.profile.attribute.model

import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileAttributeInputTest {

    @Test
    fun `ProfileAttributeInput creation with required fields preserves values`() {
        val input = ProfileAttributeInput(
            typeId = "contact",
            visibility = ProfileVisibility.USER,
            confidence = 90,
            priority = 1,
            source = "manual"
        )
        assertEquals("contact", input.typeId)
        assertEquals(ProfileVisibility.USER, input.visibility)
        assertEquals(90, input.confidence)
        assertEquals(1, input.priority)
        assertEquals("manual", input.source)
    }

    @Test
    fun `ProfileAttributeInput id defaults to NIL`() {
        val input = ProfileAttributeInput(
            typeId = "test",
            visibility = ProfileVisibility.PUBLIC,
            confidence = 50,
            priority = 0,
            source = "system"
        )
        assertEquals(UUID.NIL, input.id)
    }

    @Test
    fun `ProfileAttributeInput optional fields default to null`() {
        val input = ProfileAttributeInput(
            typeId = "test",
            visibility = ProfileVisibility.PUBLIC,
            confidence = 50,
            priority = 0,
            source = "system"
        )
        assertNull(input.attributes)
        assertNull(input.metadataId)
        assertNull(input.metadataSupplementary)
        assertNull(input.expiration)
    }

    @Test
    fun `ProfileAttributeInput creation with all fields`() {
        val id = UUID.random()
        val metadataId = UUID.random()
        val attrs = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val input = ProfileAttributeInput(
            id = id,
            typeId = "work",
            visibility = ProfileVisibility.FRIENDS,
            confidence = 100,
            priority = 5,
            source = "import",
            attributes = attrs,
            metadataId = metadataId,
            metadataSupplementary = "extra-data"
        )
        assertEquals(id, input.id)
        assertEquals("work", input.typeId)
        assertEquals(ProfileVisibility.FRIENDS, input.visibility)
        assertEquals(100, input.confidence)
        assertEquals(5, input.priority)
        assertEquals("import", input.source)
        assertEquals(attrs, input.attributes)
        assertEquals(metadataId, input.metadataId)
        assertEquals("extra-data", input.metadataSupplementary)
    }

    @Test
    fun `ProfileAttributeInput data class equality`() {
        val a = ProfileAttributeInput(
            typeId = "contact",
            visibility = ProfileVisibility.USER,
            confidence = 80,
            priority = 2,
            source = "api"
        )
        val b = ProfileAttributeInput(
            typeId = "contact",
            visibility = ProfileVisibility.USER,
            confidence = 80,
            priority = 2,
            source = "api"
        )
        assertEquals(a, b)
    }

    @Test
    fun `ProfileAttributeInput copy changes specific fields`() {
        val original = ProfileAttributeInput(
            typeId = "contact",
            visibility = ProfileVisibility.USER,
            confidence = 80,
            priority = 2,
            source = "api"
        )
        val copy = original.copy(confidence = 95, source = "verified")
        assertEquals(95, copy.confidence)
        assertEquals("verified", copy.source)
        assertEquals(original.typeId, copy.typeId)
        assertEquals(original.visibility, copy.visibility)
        assertEquals(original.priority, copy.priority)
    }

    @Test
    fun `ProfileAttributeInput with different visibility values`() {
        ProfileVisibility.entries.forEach { visibility ->
            val input = ProfileAttributeInput(
                typeId = "test",
                visibility = visibility,
                confidence = 50,
                priority = 0,
                source = "test"
            )
            assertEquals(visibility, input.visibility)
        }
    }

    @Test
    fun `ProfileAttributeInput with zero confidence and priority`() {
        val input = ProfileAttributeInput(
            typeId = "test",
            visibility = ProfileVisibility.SYSTEM,
            confidence = 0,
            priority = 0,
            source = "default"
        )
        assertEquals(0, input.confidence)
        assertEquals(0, input.priority)
    }

    @Test
    fun `ProfileAttributeInput hashCode is consistent for equal instances`() {
        val a = ProfileAttributeInput(
            typeId = "t",
            visibility = ProfileVisibility.PUBLIC,
            confidence = 1,
            priority = 1,
            source = "s"
        )
        val b = ProfileAttributeInput(
            typeId = "t",
            visibility = ProfileVisibility.PUBLIC,
            confidence = 1,
            priority = 1,
            source = "s"
        )
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ProfileAttributeInput toString contains field values`() {
        val input = ProfileAttributeInput(
            typeId = "contact",
            visibility = ProfileVisibility.USER,
            confidence = 50,
            priority = 1,
            source = "manual"
        )
        val str = input.toString()
        assertTrue(str.contains("contact"))
        assertTrue(str.contains("manual"))
    }
}
