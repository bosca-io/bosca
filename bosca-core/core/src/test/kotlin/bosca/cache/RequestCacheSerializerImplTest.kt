package bosca.cache

import bosca.serialization.UUIDSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

@Serializable
data class SimpleItem(val id: Int, val name: String)

@Serializable
data class NestedItem(val label: String, val child: SimpleItem)

@OptIn(ExperimentalSerializationApi::class)
class RequestCacheSerializerImplTest {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(JsonElement::class, JsonElement.serializer())
        }
    }

    private val serializer: RequestCacheSerializer = RequestCacheSerializerImpl(json)

    // --- serialize null ---

    @Test
    fun `serialize null returns null`() {
        assertNull(serializer.serialize(null))
    }

    // --- deserialize null ---

    @Test
    fun `deserialize null returns null`() {
        assertNull(serializer.deserialize(null))
    }

    @Test
    fun `deserialize empty string returns null`() {
        assertNull(serializer.deserialize(""))
    }

    // --- Single object round-trip ---

    @Test
    fun `round-trip serialization of simple object`() {
        val item = SimpleItem(id = 1, name = "test")
        val serialized = serializer.serialize(item)
        assertNotNull(serialized)
        val deserialized = serializer.deserialize(serialized)
        assertEquals(item, deserialized)
    }

    @Test
    fun `round-trip serialization of nested object`() {
        val item = NestedItem(label = "parent", child = SimpleItem(id = 2, name = "child"))
        val serialized = serializer.serialize(item)
        assertNotNull(serialized)
        val deserialized = serializer.deserialize(serialized)
        assertEquals(item, deserialized)
    }

    // --- UUID round-trip ---

    @Test
    fun `round-trip serialization of UUID`() {
        val uuid = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val serialized = serializer.serialize(uuid)
        assertNotNull(serialized)
        val deserialized = serializer.deserialize(serialized)
        assertEquals(uuid, deserialized)
    }

    @Test
    fun `round-trip serialization of nil UUID`() {
        val uuid = Uuid.parse("00000000-0000-0000-0000-000000000000")
        val serialized = serializer.serialize(uuid)
        assertNotNull(serialized)
        val deserialized = serializer.deserialize(serialized)
        assertEquals(uuid, deserialized)
    }

    // --- Collection round-trip ---

    @Test
    fun `round-trip serialization of list of objects`() {
        val items = listOf(
            SimpleItem(id = 1, name = "first"),
            SimpleItem(id = 2, name = "second"),
            SimpleItem(id = 3, name = "third"),
        )
        val serialized = serializer.serialize(items)
        assertNotNull(serialized)
        val deserialized = serializer.deserialize(serialized)
        assertEquals(items, deserialized)
    }

    @Test
    fun `round-trip serialization of list of UUIDs`() {
        val uuids = listOf(
            Uuid.parse("550e8400-e29b-41d4-a716-446655440000"),
            Uuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
        )
        val serialized = serializer.serialize(uuids)
        assertNotNull(serialized)
        val deserialized = serializer.deserialize(serialized)
        assertEquals(uuids, deserialized)
    }

    @Test
    fun `serialize empty collection returns null`() {
        val result = serializer.serialize(emptyList<SimpleItem>())
        assertNull(result)
    }

    // --- Set collection round-trip ---

    @Test
    fun `round-trip serialization of set of objects converts to list`() {
        val items = setOf(
            SimpleItem(id = 1, name = "first"),
            SimpleItem(id = 2, name = "second"),
        )
        val serialized = serializer.serialize(items)
        assertNotNull(serialized)
        val deserialized = serializer.deserialize(serialized)
        assertEquals(items.toList(), deserialized)
    }

    // --- String round-trip ---

    @Test
    fun `round-trip serialization of String`() {
        val value = "hello world"
        val serialized = serializer.serialize(value)
        assertNotNull(serialized)
        val deserialized = serializer.deserialize(serialized)
        assertEquals(value, deserialized)
    }

    // --- Int round-trip ---

    @Test
    fun `round-trip serialization of Int`() {
        val value = 42
        val serialized = serializer.serialize(value)
        assertNotNull(serialized)
        val deserialized = serializer.deserialize(serialized)
        assertEquals(value, deserialized)
    }

    // --- Long round-trip ---

    @Test
    fun `round-trip serialization of Long`() {
        val value = 123456789L
        val serialized = serializer.serialize(value)
        assertNotNull(serialized)
        val deserialized = serializer.deserialize(serialized)
        assertEquals(value, deserialized)
    }

    // --- Boolean round-trip ---

    @Test
    fun `round-trip serialization of Boolean`() {
        val serializedTrue = serializer.serialize(true)
        assertNotNull(serializedTrue)
        assertEquals(true, serializer.deserialize(serializedTrue))

        val serializedFalse = serializer.serialize(false)
        assertNotNull(serializedFalse)
        assertEquals(false, serializer.deserialize(serializedFalse))
    }

    @Test
    fun `round-trip serialization covers remaining Kotlin primitive serial names`() {
        listOf<Any>(1.5, 2.5f, 3.toShort(), 4.toByte(), 'x').forEach { value ->
            val serialized = serializer.serialize(value)
            assertNotNull(serialized)
            assertEquals(value, serializer.deserialize(serialized))
        }
    }

    @Test
    fun `deserialization handles absent and invalid stored class names`() {
        assertNull(serializer.deserialize("""{"collection":false,"data":"orphan"}"""))
        assertFailsWith<ClassNotFoundException> {
            serializer.deserialize("""{"className":"missing.example.Type","collection":false,"data":{}}""")
        }
        assertNull(serializer.deserialize(
            """{"className":"bosca.cache.SimpleItem","collection":false,"data":{"id":"wrong","name":4}}""",
        ))
        assertNull(serializer.deserialize(
            """{"className":"bosca.cache.SimpleItem","collection":true,"data":{"id":1,"name":"one"}}""",
        ))
    }

    // --- Deserialization of wrapper with null data ---

    @Test
    fun `deserialize wrapper with null data returns null`() {
        val jsonStr = """{"className":"bosca.cache.SimpleItem","collection":false,"data":null}"""
        val result = serializer.deserialize(jsonStr)
        assertNull(result)
    }

    @Test
    fun `deserialize wrapper with absent data returns null`() {
        val jsonStr = """{"className":"bosca.cache.SimpleItem","collection":false}"""
        val result = serializer.deserialize(jsonStr)
        assertNull(result)
    }

    // --- Stored envelope compatibility ---

    @Test
    fun `serialize writes the stored envelope layout readers on older versions expect`() {
        assertEquals(
            """{"className":"bosca.cache.SimpleItem","data":{"id":1,"name":"one"}}""",
            serializer.serialize(SimpleItem(1, "one")),
        )
        assertEquals(
            """{"className":"bosca.cache.SimpleItem","collection":true,"data":[{"id":1,"name":"one"},{"id":2,"name":"two"}]}""",
            serializer.serialize(listOf(SimpleItem(1, "one"), SimpleItem(2, "two"))),
        )
    }

    @Test
    fun `envelopes with data before className or collection still decode`() {
        assertEquals(
            SimpleItem(1, "one"),
            serializer.deserialize("""{"data":{"id":1,"name":"one"},"className":"bosca.cache.SimpleItem"}"""),
        )
        assertEquals(
            listOf(SimpleItem(1, "one")),
            serializer.deserialize("""{"className":"bosca.cache.SimpleItem","data":[{"id":1,"name":"one"}],"collection":true}"""),
        )
    }

    @Test
    fun `malformed envelopes still fail and unknown classes without data are absent`() {
        assertFailsWith<SerializationException> {
            serializer.deserialize("""{"className":"bosca.cache.SimpleItem","data":{"id":1,""")
        }
        assertNull(serializer.deserialize("""{"className":"missing.example.Type","data":null}"""))
    }

    @Test
    fun `repeated values of one type decode through the cached serializer`() {
        val stored = List(3) { serializer.serialize(SimpleItem(it, "item-$it")) }
        assertEquals(List(3) { SimpleItem(it, "item-$it") }, stored.map { serializer.deserialize(it) })
        assertEquals(List(3) { SimpleItem(it, "item-$it") }, stored.map { serializer.deserialize(it) })
    }
}
