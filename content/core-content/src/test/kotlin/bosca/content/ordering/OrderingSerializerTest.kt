package bosca.content.ordering

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrderingSerializerTest {

    private val json = Json

    // --- OrderingInput serialization ---

    @Test
    fun `OrderingInput serializes and deserializes with all fields`() {
        val input = OrderingInput(
            field = "name",
            location = AttributeLocation.ITEM,
            order = Order.ASCENDING,
            path = listOf("attrs", "name"),
            type = AttributeType.STRING
        )
        val serialized = json.encodeToString(OrderingInput.serializer(), input)
        val deserialized = json.decodeFromString(OrderingInput.serializer(), serialized)
        assertEquals(input.field, deserialized.field)
        assertEquals(input.location, deserialized.location)
        assertEquals(input.order, deserialized.order)
        assertEquals(input.path, deserialized.path)
        assertEquals(input.type, deserialized.type)
    }

    @Test
    fun `OrderingInput serializes with null fields`() {
        val input = OrderingInput()
        val serialized = json.encodeToString(OrderingInput.serializer(), input)
        val deserialized = json.decodeFromString(OrderingInput.serializer(), serialized)
        assertNull(deserialized.field)
        assertNull(deserialized.location)
        assertNull(deserialized.order)
        assertNull(deserialized.path)
        assertNull(deserialized.type)
    }

    // --- AttributeLocationSerializer ---

    @Test
    fun `AttributeLocationSerializer serializes to lowercase`() {
        val input = OrderingInput(location = AttributeLocation.ITEM)
        val serialized = json.encodeToString(OrderingInput.serializer(), input)
        assert(serialized.contains("\"item\""))
    }

    @Test
    fun `AttributeLocationSerializer deserializes from uppercase`() {
        val jsonStr = """{"location":"ITEM"}"""
        val deserialized = json.decodeFromString(OrderingInput.serializer(), jsonStr)
        assertEquals(AttributeLocation.ITEM, deserialized.location)
    }

    @Test
    fun `AttributeLocationSerializer deserializes RELATIONSHIP`() {
        val jsonStr = """{"location":"relationship"}"""
        val deserialized = json.decodeFromString(OrderingInput.serializer(), jsonStr)
        assertEquals(AttributeLocation.RELATIONSHIP, deserialized.location)
    }

    // --- OrderSerializer ---

    @Test
    fun `OrderSerializer serializes to lowercase`() {
        val input = OrderingInput(order = Order.DESCENDING)
        val serialized = json.encodeToString(OrderingInput.serializer(), input)
        assert(serialized.contains("\"descending\""))
    }

    @Test
    fun `OrderSerializer deserializes ASCENDING`() {
        val jsonStr = """{"order":"ascending"}"""
        val deserialized = json.decodeFromString(OrderingInput.serializer(), jsonStr)
        assertEquals(Order.ASCENDING, deserialized.order)
    }

    @Test
    fun `OrderSerializer deserializes DESCENDING from uppercase`() {
        val jsonStr = """{"order":"DESCENDING"}"""
        val deserialized = json.decodeFromString(OrderingInput.serializer(), jsonStr)
        assertEquals(Order.DESCENDING, deserialized.order)
    }

    // --- AttributeTypeLocationSerializer ---

    @Test
    fun `AttributeTypeLocationSerializer handles DATETIME to DATE_TIME conversion`() {
        val jsonStr = """{"type":"datetime"}"""
        val deserialized = json.decodeFromString(OrderingInput.serializer(), jsonStr)
        assertEquals(AttributeType.DATE_TIME, deserialized.type)
    }

    @Test
    fun `AttributeTypeLocationSerializer handles normal types`() {
        val jsonStr = """{"type":"string"}"""
        val deserialized = json.decodeFromString(OrderingInput.serializer(), jsonStr)
        assertEquals(AttributeType.STRING, deserialized.type)
    }

    @Test
    fun `AttributeTypeLocationSerializer handles INT type`() {
        val jsonStr = """{"type":"int"}"""
        val deserialized = json.decodeFromString(OrderingInput.serializer(), jsonStr)
        assertEquals(AttributeType.INT, deserialized.type)
    }

    @Test
    fun `AttributeTypeLocationSerializer serializes to lowercase`() {
        val input = OrderingInput(type = AttributeType.FLOAT)
        val serialized = json.encodeToString(OrderingInput.serializer(), input)
        assert(serialized.contains("\"float\""))
    }

    // --- Ordering toString ---

    @Test
    fun `Ordering toString concatenates all fields`() {
        val ordering = Ordering(
            field = "name",
            location = AttributeLocation.ITEM,
            order = Order.ASCENDING,
            path = listOf("a", "b"),
            type = AttributeType.STRING
        )
        val str = ordering.toString()
        assert(str.contains("name"))
        assert(str.contains("ITEM"))
        assert(str.contains("ASCENDING"))
        assert(str.contains("a"))
        assert(str.contains("b"))
        assert(str.contains("STRING"))
    }

    @Test
    fun `Ordering toString handles null path`() {
        val ordering = Ordering(field = "name")
        val str = ordering.toString()
        assert(str.contains("name"))
    }
}
