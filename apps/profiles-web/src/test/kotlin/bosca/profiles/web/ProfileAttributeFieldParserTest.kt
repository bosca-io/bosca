package bosca.profiles.web

import bosca.profiles.web.graphql.ProfileVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ProfileAttributeFieldParserTest {
    @Test
    fun `text area schema becomes a native BML field`() {
        val schema = Json.parseToJsonElement(
            """{"type":"object","required":["bio"],"properties":{"bio":{"type":"string","title":"Biography"}}}""",
        )
        val uiSchema = Json.parseToJsonElement(
            """{"layout":[{"type":"field","property":"bio","control":"textarea","rows":8,"placeholder":"About you"}]}""",
        )
        val value = Json.parseToJsonElement("""{"bio":"Hello"}""")

        val field = profileAttributeField(schema, uiSchema, value)

        assertEquals("bio", field.key)
        assertEquals("Biography", field.label)
        assertEquals("Hello", field.value)
        assertEquals(8, field.rows)
        assertEquals("About you", field.placeholder)
        assertTrue(field.required)
        assertTrue(field.textarea)
        assertFalse(field.boolean)
    }

    @Test
    fun `boolean schema preserves its value and enum visibility`() {
        val schema = Json.parseToJsonElement(
            """{"type":"object","properties":{"enabled":{"type":"boolean","title":"Enabled"}}}""",
        )
        val uiSchema = Json.parseToJsonElement(
            """{"layout":[{"type":"field","property":"enabled","control":"switch"}]}""",
        )
        val field = profileAttributeField(schema, uiSchema, Json.parseToJsonElement("""{"enabled":true}"""))
        val type = ProfileAttributeTypeRow("type", "Type", "", ProfileVisibility.FRIENDS, false, field)

        assertTrue(field.boolean)
        assertTrue(field.checked)
        assertEquals(ProfileVisibility.FRIENDS, type.visibility)
    }
}
