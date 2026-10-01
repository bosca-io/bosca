package bosca.forms.model

import bosca.profile.model.ProfileVisibility
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class FormSchemaProfileMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serializes and deserializes a complete profile mapping`() {
        val mapping = FormSchemaProfileMapping(
            nameField = "name",
            visibility = ProfileVisibility.USER,
            attributes = listOf(
                FormSchemaProfileMappingAttribute(
                    typeId = "bosca.profiles.email",
                    field = "email",
                    attributeKey = "email"
                ),
                FormSchemaProfileMappingAttribute(
                    typeId = "bosca.profiles.name",
                    field = "name",
                    attributeKey = "name"
                )
            )
        )
        val serialized = json.encodeToString(FormSchemaProfileMapping.serializer(), mapping)
        val deserialized = json.decodeFromString(FormSchemaProfileMapping.serializer(), serialized)
        assertEquals(mapping, deserialized)
    }

    @Test
    fun `deserializes with empty attributes list`() {
        val jsonStr = """{"nameField":"full_name","visibility":"PUBLIC","attributes":[]}"""
        val mapping = json.decodeFromString(FormSchemaProfileMapping.serializer(), jsonStr)
        assertEquals("full_name", mapping.nameField)
        assertEquals(ProfileVisibility.PUBLIC, mapping.visibility)
        assertEquals(emptyList(), mapping.attributes)
    }

    @Test
    fun `defaults attributes to empty list`() {
        val mapping = FormSchemaProfileMapping(
            nameField = "name",
            visibility = ProfileVisibility.USER
        )
        assertEquals(emptyList(), mapping.attributes)
    }

    @Test
    fun `attribute fields round-trip correctly`() {
        val attr = FormSchemaProfileMappingAttribute(
            typeId = "bosca.profiles.phone",
            field = "phone_number",
            attributeKey = "phone"
        )
        val serialized = json.encodeToString(FormSchemaProfileMappingAttribute.serializer(), attr)
        val deserialized = json.decodeFromString(FormSchemaProfileMappingAttribute.serializer(), serialized)
        assertEquals(attr, deserialized)
    }

    @Test
    fun `deserializes from jsonElement for database column mapping`() {
        val mapping = FormSchemaProfileMapping(
            nameField = "name",
            visibility = ProfileVisibility.USER,
            attributes = listOf(
                FormSchemaProfileMappingAttribute("bosca.profiles.email", "email", "email")
            )
        )
        val element = json.encodeToJsonElement(FormSchemaProfileMapping.serializer(), mapping)
        val decoded = json.decodeFromJsonElement(FormSchemaProfileMapping.serializer(), element)
        assertEquals(mapping, decoded)
    }
}
