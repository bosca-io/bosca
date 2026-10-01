package bosca.profile.attribute.graphql

import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.ProfileVisibility
import bosca.serialization.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileAttributeControllerTest {

    private val controller = ProfileAttributeController(
        service = io.mockk.mockk(),
        metadataService = io.mockk.mockk(),
        metadataPermissionEvaluator = io.mockk.mockk()
    )

    private fun createAttribute(
        id: UUID = UUID.random(),
        typeId: String = "bosca.profiles.email",
        visibility: ProfileVisibility = ProfileVisibility.USER,
        confidence: Int = 100,
        priority: Int = 1,
        source: String = "test",
        created: OffsetDateTime = OffsetDateTime.now()
    ): ProfileAttribute {
        return ProfileAttribute(
            id = id,
            profile = UUID.random(),
            typeId = typeId,
            visibility = visibility,
            confidence = confidence,
            priority = priority,
            source = source,
            created = created
        )
    }

    @Test
    fun `id returns attribute id`() {
        val id = UUID.random()
        val attribute = createAttribute(id = id)

        assertEquals(id, controller.id(attribute))
    }

    @Test
    fun `typeId returns attribute type id`() {
        val attribute = createAttribute(typeId = "bosca.profiles.name")

        assertEquals("bosca.profiles.name", controller.typeId(attribute))
    }

    @Test
    fun `source returns attribute source`() {
        val attribute = createAttribute(source = "oauth2")

        assertEquals("oauth2", controller.source(attribute))
    }

    @Test
    fun `priority returns attribute priority`() {
        val attribute = createAttribute(priority = 5)

        assertEquals(5, controller.priority(attribute))
    }

    @Test
    fun `confidence returns attribute confidence`() {
        val attribute = createAttribute(confidence = 75)

        assertEquals(75, controller.confidence(attribute))
    }

    @Test
    fun `visibility returns attribute visibility`() {
        val attribute = createAttribute(visibility = ProfileVisibility.PUBLIC)

        assertEquals(ProfileVisibility.PUBLIC, controller.visibility(attribute))
    }

    @Test
    fun `attributes returns attribute attributes`() {
        val attrs = buildJsonObject { put("email", "test@example.com") }
        val attribute = createAttribute().copy(attributes = attrs)

        assertEquals(attrs, controller.attributes(attribute))
    }

    @Test
    fun `attributes returns null when not set`() {
        val attribute = createAttribute()

        assertNull(controller.attributes(attribute))
    }

    @Test
    fun `created returns attribute creation timestamp`() {
        val timestamp = OffsetDateTime.parse("2024-01-01T00:00:00Z")
        val attribute = createAttribute(created = timestamp)

        assertEquals(timestamp, controller.created(attribute))
    }

    @Test
    fun `expires returns attribute expiry`() {
        val attribute = createAttribute()

        assertNull(controller.expires(attribute))
    }
}
