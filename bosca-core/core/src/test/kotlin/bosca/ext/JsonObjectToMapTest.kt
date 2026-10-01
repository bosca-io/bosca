package bosca.ext

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the conversion between [JsonObject] and plain [Map] representations,
 * as well as the round-trip behavior of [anyToJsonElement] for common JVM types.
 */
class JsonObjectToMapTest {

    // --- jsonObjectToMap ---

    @Test
    fun `jsonObjectToMap converts string values`() {
        val json = JsonObject(mapOf("name" to JsonPrimitive("Alice")))
        val map = jsonObjectToMap(json)
        assertEquals("Alice", map["name"])
    }

    @Test
    fun `jsonObjectToMap converts nested JsonObject`() {
        val inner = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val json = JsonObject(mapOf("nested" to inner))
        val map = jsonObjectToMap(json)
        val nested = map["nested"]
        assertIs<Map<*, *>>(nested)
        assertEquals("value", nested["key"])
    }

    @Test
    fun `jsonObjectToMap converts JsonArray with mixed types`() {
        val array = JsonArray(listOf(
            JsonPrimitive("text"),
            JsonPrimitive(42),
            JsonPrimitive(true),
            JsonNull
        ))
        val json = JsonObject(mapOf("items" to array))
        val map = jsonObjectToMap(json)
        val items = map["items"]
        assertIs<List<*>>(items)
        assertEquals("text", items[0])
        assertEquals(42L, items[1])
        assertEquals(true, items[2])
        assertNull(items[3])
    }

    @Test
    fun `jsonObjectToMap handles JsonNull`() {
        val json = JsonObject(mapOf("nothing" to JsonNull))
        val map = jsonObjectToMap(json)
        assertNull(map["nothing"])
    }

    @Test
    fun `jsonObjectToMap handles boolean primitives`() {
        val json = JsonObject(mapOf(
            "yes" to JsonPrimitive(true),
            "no" to JsonPrimitive(false)
        ))
        val map = jsonObjectToMap(json)
        assertEquals(true, map["yes"])
        assertEquals(false, map["no"])
    }

    @Test
    fun `jsonObjectToMap handles integer number primitives`() {
        val json = JsonObject(mapOf("count" to JsonPrimitive(7)))
        val map = jsonObjectToMap(json)
        assertEquals(7L, map["count"])
    }

    @Test
    fun `jsonObjectToMap handles double number primitives`() {
        val json = JsonObject(mapOf("price" to JsonPrimitive(9.99)))
        val map = jsonObjectToMap(json)
        assertEquals(9.99, map["price"])
    }

    @Test
    fun `jsonObjectToMap preserves malformed decimal text and parses scientific numbers`() {
        val json = kotlinx.serialization.json.Json { isLenient = true }
        val values = JsonObject(
            mapOf(
                "scientific" to json.parseToJsonElement("1e3"),
                "notNumber" to json.parseToJsonElement("1.2.3"),
            ),
        )

        assertEquals(1000.0, jsonObjectToMap(values)["scientific"])
        assertEquals("1.2.3", jsonObjectToMap(values)["notNumber"])
    }

    // --- anyToJsonElement ---

    @Test
    fun `anyToJsonElement with null returns JsonNull`() {
        assertEquals(JsonNull, anyToJsonElement(null))
    }

    @Test
    fun `anyToJsonElement with Map returns JsonObject`() {
        val result = anyToJsonElement(mapOf("key" to "value"))
        assertIs<JsonObject>(result)
        assertEquals(JsonPrimitive("value"), result["key"])
    }

    @Test
    fun `anyToJsonElement with List returns JsonArray`() {
        val result = anyToJsonElement(listOf(1, "two", true))
        assertIs<JsonArray>(result)
        assertEquals(3, result.size)
    }

    @Test
    fun `anyToJsonElement with Boolean returns JsonPrimitive`() {
        assertEquals(JsonPrimitive(true), anyToJsonElement(true))
        assertEquals(JsonPrimitive(false), anyToJsonElement(false))
    }

    @Test
    fun `anyToJsonElement with Number returns JsonPrimitive`() {
        assertEquals(JsonPrimitive(42), anyToJsonElement(42))
        assertEquals(JsonPrimitive(3.14), anyToJsonElement(3.14))
    }

    @Test
    fun `anyToJsonElement with String returns JsonPrimitive`() {
        assertEquals(JsonPrimitive("hello"), anyToJsonElement("hello"))
    }

    @Test
    fun `anyToJsonElement round-trip preserves structure`() {
        val original = mapOf(
            "name" to "test",
            "count" to 5,
            "active" to true,
            "tags" to listOf("a", "b"),
            "empty" to null
        )
        val jsonElement = anyToJsonElement(original)
        assertIs<JsonObject>(jsonElement)
        val roundTripped = jsonObjectToMap(jsonElement as JsonObject)
        assertEquals("test", roundTripped["name"])
        assertEquals(5L, roundTripped["count"])
        assertEquals(true, roundTripped["active"])
        assertTrue(roundTripped["tags"] is List<*>)
        assertNull(roundTripped["empty"])
    }

    @Test
    fun `anyToJsonElement covers existing elements arrays and fallback objects`() {
        val existing = JsonPrimitive("existing")
        assertTrue(anyToJsonElement(existing) === existing)
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), anyToJsonElement(arrayOf(1, 2)))
        assertEquals(JsonPrimitive("fallback"), anyToJsonElement(object {
            override fun toString() = "fallback"
        }))
    }
}
