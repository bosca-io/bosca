package bosca.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ParametersTest {

    @Test
    fun `get returns first value for key`() {
        val params = Parameters(mapOf("name" to listOf("first", "second")))
        assertEquals("first", params["name"])
    }

    @Test
    fun `get returns null for missing key`() {
        val params = Parameters(mapOf("name" to listOf("value")))
        assertNull(params["missing"])
    }

    @Test
    fun `getAll returns all values for key`() {
        val params = Parameters(mapOf("tag" to listOf("a", "b", "c")))
        assertEquals(listOf("a", "b", "c"), params.getAll("tag"))
    }

    @Test
    fun `getAll returns empty list for missing key`() {
        val params = Parameters(mapOf("tag" to listOf("a")))
        assertEquals(emptyList(), params.getAll("missing"))
    }

    @Test
    fun `contains returns true for existing key`() {
        val params = Parameters(mapOf("key" to listOf("value")))
        assertTrue("key" in params)
    }

    @Test
    fun `contains returns false for missing key`() {
        val params = Parameters(mapOf("key" to listOf("value")))
        assertFalse("missing" in params)
    }

    @Test
    fun `names returns all parameter names`() {
        val params = Parameters(mapOf("a" to listOf("1"), "b" to listOf("2")))
        assertEquals(setOf("a", "b"), params.names)
    }

    @Test
    fun `isEmpty returns true for empty parameters`() {
        val params = Parameters()
        assertTrue(params.isEmpty)
    }

    @Test
    fun `isEmpty returns false for non-empty parameters`() {
        val params = Parameters(mapOf("key" to listOf("value")))
        assertFalse(params.isEmpty)
    }

    @Test
    fun `getOrFail returns value as String`() {
        val params = Parameters(mapOf("name" to listOf("hello")))
        assertEquals("hello", params.getOrFail<String>("name"))
    }

    @Test
    fun `getOrFail throws on missing parameter`() {
        val params = Parameters()
        assertFailsWith<IllegalArgumentException> {
            params.getOrFail<String>("missing")
        }
    }

    @Test
    fun `getOrFail converts to Int`() {
        val params = Parameters(mapOf("count" to listOf("42")))
        assertEquals(42, params.getOrFail<Int>("count"))
    }

    @Test
    fun `getOrFail converts to Long`() {
        val params = Parameters(mapOf("id" to listOf("9999999999")))
        assertEquals(9999999999L, params.getOrFail<Long>("id"))
    }

    @Test
    fun `getOrFail converts to Boolean`() {
        val params = Parameters(mapOf("flag" to listOf("true")))
        assertEquals(true, params.getOrFail<Boolean>("flag"))
    }

    @Test
    fun `getOrFail converts to Float`() {
        val params = Parameters(mapOf("ratio" to listOf("3.14")))
        assertEquals(3.14f, params.getOrFail<Float>("ratio"))
    }

    @Test
    fun `numeric accessors cover double float and invalid conversions`() {
        val valid = Parameters(mapOf("double" to listOf("2.5"), "float" to listOf("1.25")))
        assertEquals(2.5, valid.getOrNull<Double>("double"))
        assertEquals(1.25f, valid.getOrNull<Float>("float"))
        assertEquals(2.5, valid.getOrFail<Double>("double"))

        val invalid = Parameters(mapOf("long" to listOf("x"), "double" to listOf("x"), "float" to listOf("x")))
        assertNull(invalid.getOrNull<Long>("long"))
        assertNull(invalid.getOrNull<Double>("double"))
        assertNull(invalid.getOrNull<Float>("float"))
        assertFailsWith<IllegalArgumentException> { invalid.getOrFail<Long>("long") }
        assertFailsWith<IllegalArgumentException> { invalid.getOrFail<Double>("double") }
        assertFailsWith<IllegalArgumentException> { invalid.getOrFail<Float>("float") }
    }

    @Test
    fun `unsupported accessor type follows cast behavior`() {
        val params = Parameters(mapOf("value" to listOf("text")))
        assertEquals("text", params.getOrNull<Any>("value"))
        assertNull(params.getOrNull<StringBuilder>("value"))
        assertFailsWith<ClassCastException> { params.getOrFail<StringBuilder>("value") }
    }

    @Test
    fun `getOrFail throws on invalid Int conversion`() {
        val params = Parameters(mapOf("count" to listOf("not-a-number")))
        assertFailsWith<IllegalArgumentException> {
            params.getOrFail<Int>("count")
        }
    }

    @Test
    fun `getOrFail throws on invalid Boolean conversion`() {
        val params = Parameters(mapOf("flag" to listOf("maybe")))
        assertFailsWith<IllegalArgumentException> {
            params.getOrFail<Boolean>("flag")
        }
    }

    @Test
    fun `getOrNull returns value as String`() {
        val params = Parameters(mapOf("name" to listOf("hello")))
        assertEquals("hello", params.getOrNull<String>("name"))
    }

    @Test
    fun `getOrNull returns null for missing parameter`() {
        val params = Parameters()
        assertNull(params.getOrNull<String>("missing"))
    }

    @Test
    fun `getOrNull converts to Int`() {
        val params = Parameters(mapOf("count" to listOf("42")))
        assertEquals(42, params.getOrNull<Int>("count"))
    }

    @Test
    fun `getOrNull returns null for invalid Int`() {
        val params = Parameters(mapOf("count" to listOf("abc")))
        assertNull(params.getOrNull<Int>("count"))
    }

    @Test
    fun `getOrNull converts to Long`() {
        val params = Parameters(mapOf("id" to listOf("100")))
        assertEquals(100L, params.getOrNull<Long>("id"))
    }

    @Test
    fun `getOrNull converts to Boolean`() {
        val params = Parameters(mapOf("flag" to listOf("false")))
        assertEquals(false, params.getOrNull<Boolean>("flag"))
    }

    @Test
    fun `Empty singleton is empty`() {
        assertTrue(Parameters.Empty.isEmpty)
        assertEquals(emptySet(), Parameters.Empty.names)
        assertNull(Parameters.Empty["anything"])
    }

    @Test
    fun `fromSingleValueMap creates parameters from map`() {
        val params = Parameters.fromSingleValueMap(mapOf("a" to "1", "b" to "2"))
        assertEquals("1", params["a"])
        assertEquals("2", params["b"])
        assertEquals(listOf("1"), params.getAll("a"))
    }

    @Test
    fun `fromSingleValueMap reuses Empty for empty map`() {
        assertSame(Parameters.Empty, Parameters.fromSingleValueMap(emptyMap()))
    }

    @Test
    fun `fromPairs creates parameters from list of pairs`() {
        val params = Parameters.fromPairs(listOf("tag" to "a", "tag" to "b", "name" to "test"))
        assertEquals("a", params["tag"])
        assertEquals(listOf("a", "b"), params.getAll("tag"))
        assertEquals("test", params["name"])
    }

    @Test
    fun `fromPairs with empty list creates empty parameters`() {
        val params = Parameters.fromPairs(emptyList())
        assertTrue(params.isEmpty)
    }

    @Test
    fun `delegate getValue returns parameter by property name`() {
        val params = Parameters(mapOf("id" to listOf("123")))
        val id by params
        assertEquals("123", id)
    }

    @Test
    fun `toJsonElement converts parameters to JsonObject`() {
        val params = Parameters(mapOf("tag" to listOf("a", "b"), "name" to listOf("test")))
        val json = params.toJsonElement()
        val obj = json as JsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), obj["tag"])
        assertEquals(JsonArray(listOf(JsonPrimitive("test"))), obj["name"])
    }

    @Test
    fun `toJsonElement on empty parameters returns empty JsonObject`() {
        val json = Parameters.Empty.toJsonElement()
        assertEquals(JsonObject(emptyMap()), json)
    }

    @Test
    fun `delegate getValue throws on missing parameter`() {
        val params = Parameters()
        assertFailsWith<IllegalStateException> {
            val missing by params
            @Suppress("UNUSED_EXPRESSION")
            missing
        }
    }
}
