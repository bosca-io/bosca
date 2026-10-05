package bosca.bml.message

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PayloadSchemaTest {

    @Serializable
    private enum class Tier { BASIC, PRO }

    @Serializable
    private data class Highlight(val title: String, val body: String = "")

    @Serializable
    private data class Payload(
        val appName: String,
        val count: Int = 0,
        val ratio: Double = 0.0,
        val active: Boolean = false,
        val tier: Tier = Tier.BASIC,
        val teacher: String? = null,
        val highlights: List<Highlight> = emptyList(),
    )

    private fun typeOf(schema: kotlinx.serialization.json.JsonElement?) =
        schema?.jsonObject?.get("type")?.jsonPrimitive?.content

    @Test
    fun `projects a class descriptor as an object schema whose required fields are those without defaults`() {
        val schema = PayloadSchema.of(Payload.serializer().descriptor).jsonObject

        assertEquals("object", typeOf(schema))
        val properties = schema["properties"]?.jsonObject ?: error("no properties: $schema")
        assertEquals("string", typeOf(properties["appName"]))
        assertEquals("integer", typeOf(properties["count"]))
        assertEquals("number", typeOf(properties["ratio"]))
        assertEquals("boolean", typeOf(properties["active"]))
        assertEquals("string", typeOf(properties["teacher"]))

        // Only the field with no Kotlin default must be present in a payload.
        assertEquals(
            listOf("appName"),
            schema["required"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `an enum field lists its values and a list field carries its element schema`() {
        val schema = PayloadSchema.of(Payload.serializer().descriptor).jsonObject
        val properties = schema["properties"]?.jsonObject ?: error("no properties: $schema")

        val tier = properties["tier"]?.jsonObject ?: error("no tier: $properties")
        assertEquals("string", typeOf(tier))
        assertEquals(listOf("BASIC", "PRO"), tier["enum"]?.jsonArray?.map { it.jsonPrimitive.content })

        val highlights = properties["highlights"]?.jsonObject ?: error("no highlights: $properties")
        assertEquals("array", typeOf(highlights))
        val items = highlights["items"]?.jsonObject ?: error("no items: $highlights")
        assertEquals("object", typeOf(items))
        assertEquals("string", typeOf(items["properties"]?.jsonObject?.get("title")))
        assertEquals(listOf("title"), items["required"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a class whose fields all default omits required entirely`() {
        val schema = PayloadSchema.of(Highlight.serializer().descriptor).jsonObject
        // `title` has no default, so Highlight itself has a required list…
        assertEquals(listOf("title"), schema["required"]?.jsonArray?.map { it.jsonPrimitive.content })

        @Serializable
        data class AllDefaults(val a: String = "", val b: Int = 0)
        assertNull(PayloadSchema.of(AllDefaults.serializer().descriptor).jsonObject["required"])
    }
}
