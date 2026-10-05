package bosca.serialization

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.mockk.mockk
import java.sql.Date
import java.sql.Time
import java.sql.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

@Serializable
private data class JsonConverterPayload(val id: Int, val name: String)

class JsonConverterTest {

    @Suppress("unused")
    private val application = BoscaApplication(mockk<ApplicationConfig>(relaxed = true))

    @Test
    fun `JsonElement toAny converts nested objects arrays and scalar types`() {
        val element = JsonObject(
            mapOf(
                "null" to JsonNull,
                "string" to JsonPrimitive("value"),
                "boolean" to JsonPrimitive(true),
                "int" to JsonPrimitive(7),
                "long" to JsonPrimitive(Long.MAX_VALUE),
                "float" to JsonPrimitive(1.5f),
                "array" to JsonArray(listOf(JsonPrimitive("first"), JsonNull)),
            )
        )

        assertEquals(
            mapOf(
                "null" to null,
                "string" to "value",
                "boolean" to true,
                "int" to 7,
                "long" to Long.MAX_VALUE,
                "float" to 1.5f,
                "array" to listOf("first", null),
            ),
            JsonConverter.run { element.toAny() },
        )
    }

    @Test
    fun `toJsonElement converts supported platform and collection values`() {
        val uuid = Uuid.parse("750e8400-e29b-41d4-a716-446655440000")
        val timestamp = Timestamp(1000)
        val date = Date(2000)
        val time = Time(3000)
        val existing = JsonPrimitive("existing")

        assertSame(JsonNull, JsonConverter.run { null.toJsonElement() })
        assertEquals(JsonPrimitive(3), JsonConverter.run { 3.toJsonElement() })
        assertEquals(JsonPrimitive("value"), JsonConverter.run { "value".toJsonElement() })
        assertEquals(JsonPrimitive(true), JsonConverter.run { true.toJsonElement() })
        assertEquals(JsonPrimitive(1000), JsonConverter.run { timestamp.toJsonElement() })
        assertEquals(JsonPrimitive(2000), JsonConverter.run { date.toJsonElement() })
        assertEquals(JsonPrimitive(3000), JsonConverter.run { time.toJsonElement() })
        assertEquals(JsonPrimitive(uuid.toString()), JsonConverter.run { uuid.toJsonElement() })
        assertSame(existing, JsonConverter.run { existing.toJsonElement() })
        assertEquals(
            JsonArray(listOf(JsonPrimitive(1), JsonNull, JsonPrimitive("three"))),
            JsonConverter.run { listOf(1, null, "three").toJsonElement() },
        )
        assertEquals(
            JsonObject(mapOf("one" to JsonPrimitive(1), "none" to JsonNull)),
            JsonConverter.run { mapOf("one" to 1, "none" to null).toJsonElement() },
        )
    }

    @Test
    fun `toJsonElement uses generated serializers for arbitrary serializable values`() {
        val payload = JsonConverterPayload(4, "payload")
        val element = JsonConverter.run { payload.toJsonElement() }

        assertEquals(JsonPrimitive(4), element.jsonObject["id"])
        assertEquals(payload, JsonConverter.run { element.asValue<JsonConverterPayload>() })
        assertEquals(element, JsonConverter.run { payload.asJsonElement() })
    }

    @Test
    fun `findSerializer resolves objects lists and the empty-list placeholder`() {
        val payload = JsonConverterPayload(1, "one")

        assertEquals(JsonConverterPayload.serializer().descriptor.serialName, JsonConverter.run {
            payload.findSerializer().descriptor.serialName
        })
        assertEquals(ListSerializer(JsonConverterPayload.serializer()).descriptor.serialName, JsonConverter.run {
            listOf(payload).findSerializer().descriptor.serialName
        })
        assertIs<AnyNullableSerializer>(JsonConverter.run { emptyList<Any>().findSerializer() })
    }

    @Test
    fun `findSerializer rejects null-first and unsupported values`() {
        assertFailsWith<IllegalStateException> {
            JsonConverter.run { listOf<JsonConverterPayload?>(null).findSerializer() }
        }
        assertFailsWith<IllegalStateException> {
            JsonConverter.run { Thread().findSerializer() }
        }
        assertFailsWith<IllegalStateException> {
            JsonConverter.run { listOf(Thread()).findSerializer() }
        }
        assertFailsWith<IllegalStateException> {
            JsonConverter.run { Thread().toJsonElement() }
        }
    }

    @Test
    fun `string parsing entry points return equivalent JSON`() {
        val source = """{"id":5,"name":"parsed"}"""
        val expected = JsonObject(mapOf("id" to JsonPrimitive(5), "name" to JsonPrimitive("parsed")))

        assertEquals(expected, JsonConverter.run { source.parseToJsonElement() })
        assertEquals(expected, JsonConverter.toJsonElement(source))
    }
}
