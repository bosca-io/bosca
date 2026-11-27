package bosca.workops.ui

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks authored wire values and lossless explicit/omitted-default serialization without reflection. */
internal fun <T> assertWorkOpsSerialization(
    serializer: KSerializer<T>,
    initial: String,
    edited: String = initial,
): T {
    val explicit = Json { encodeDefaults = true }
    var result: T? = null
    for (source in listOf(initial, edited).distinct()) {
        val expected = Json.parseToJsonElement(source)
        val model = Json.decodeFromJsonElement(serializer, expected)
        val encoded = explicit.encodeToJsonElement(serializer, model)
        assertAuthoredValues(expected, encoded, serializer.descriptor.serialName)
        val compact = Json.encodeToJsonElement(serializer, model)
        assertEquals(encoded, explicit.encodeToJsonElement(serializer, Json.decodeFromJsonElement(serializer, compact)))
        assertEquals(encoded, explicit.encodeToJsonElement(serializer, explicit.decodeFromJsonElement(serializer, encoded)))
        result = model
    }
    return requireNotNull(result)
}

private fun assertAuthoredValues(expected: JsonElement, actual: JsonElement, path: String) {
    when (expected) {
        is JsonObject -> {
            assertTrue(actual is JsonObject, path)
            for ((key, value) in expected) assertAuthoredValues(value, actual.getValue(key), "$path.$key")
        }
        is JsonArray -> {
            assertTrue(actual is JsonArray, path)
            assertEquals(expected.size, actual.size, path)
            expected.indices.forEach { assertAuthoredValues(expected[it], actual[it], "$path[$it]") }
        }
        else -> assertEquals(expected, actual, path)
    }
}
