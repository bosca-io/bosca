package bosca.profiles.web

import bosca.profiles.web.graphql.ProfileVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProfileAttributeBulkSaveTest {
    @Test
    fun `all editable attributes become one mutation input list`() {
        val textField = ProfileAttributeFieldRow(key = "name", label = "Name", value = "Before")
        val booleanField = ProfileAttributeFieldRow(key = "enabled", label = "Enabled", boolean = true)
        val attributes = listOf(
            attribute("text", "text-type", textField, ProfileVisibility.USER),
            attribute("boolean", "boolean-type", booleanField, ProfileVisibility.FRIENDS),
            attribute("json", "json-type", ProfileAttributeFieldRow(), ProfileVisibility.PUBLIC),
        )
        val model = ProfileEditorModel(
            id = "profile",
            name = "Profile",
            slug = "",
            visibility = ProfileVisibility.PUBLIC,
            searchable = true,
            created = "today",
            modified = "today",
            attributes = attributes,
            attributeTypes = listOf(
                type("text-type", textField),
                type("boolean-type", booleanField),
                type("json-type", ProfileAttributeFieldRow()),
            ),
        )
        val payload = Json.encodeToString(
            listOf(
                ProfileAttributeEdit("text", "text-type", "After", "PUBLIC"),
                ProfileAttributeEdit("boolean", "boolean-type", "true", "FRIENDS_OF_FRIENDS"),
                ProfileAttributeEdit("json", "json-type", "{\"custom\":42}", "USER"),
            ),
        )

        val inputs = profileAttributeInputs(model, payload)

        assertEquals(3, inputs.size)
        assertEquals("After", inputs[0].attributes!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(ProfileVisibility.PUBLIC, inputs[0].visibility)
        assertEquals(true, inputs[1].attributes!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(ProfileVisibility.FRIENDS_OF_FRIENDS, inputs[1].visibility)
        assertEquals(42, inputs[2].attributes!!.jsonObject["custom"]!!.jsonPrimitive.content.toInt())
        assertEquals(ProfileVisibility.USER, inputs[2].visibility)
        assertEquals(100, inputs[2].confidence)
        assertEquals("user-input", inputs[2].source)
    }

    private fun attribute(
        id: String,
        typeId: String,
        field: ProfileAttributeFieldRow,
        visibility: ProfileVisibility,
    ) = ProfileAttributeRow(
        id = id,
        typeId = typeId,
        typeName = typeId,
        description = "",
        value = "",
        source = "user-input",
        priority = 100,
        confidence = 100,
        visibility = visibility,
        expires = "",
        protected = false,
        verified = false,
        field = field,
    )

    private fun type(id: String, field: ProfileAttributeFieldRow) = ProfileAttributeTypeRow(
        id = id,
        name = id,
        description = "",
        visibility = ProfileVisibility.PUBLIC,
        protected = false,
        field = field,
    )
}
