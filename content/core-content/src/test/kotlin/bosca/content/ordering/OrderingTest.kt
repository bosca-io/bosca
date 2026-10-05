package bosca.content.ordering

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrderingTest {

    @Test
    fun `Ordering defaults all fields to null`() {
        val ordering = Ordering()
        assertNull(ordering.field)
        assertNull(ordering.location)
        assertNull(ordering.order)
        assertNull(ordering.path)
        assertNull(ordering.type)
    }

    @Test
    fun `Ordering stores all properties`() {
        val ordering = Ordering(
            field = "name",
            location = AttributeLocation.ITEM,
            order = Order.ASCENDING,
            path = listOf("a", "b"),
            type = AttributeType.STRING
        )
        assertEquals("name", ordering.field)
        assertEquals(AttributeLocation.ITEM, ordering.location)
        assertEquals(Order.ASCENDING, ordering.order)
        assertEquals(listOf("a", "b"), ordering.path)
        assertEquals(AttributeType.STRING, ordering.type)
    }

    @Test
    fun `Ordering toString includes all fields`() {
        val ordering = Ordering(
            field = "name",
            location = AttributeLocation.ITEM,
            order = Order.DESCENDING,
            path = listOf("x"),
            type = AttributeType.INT
        )
        val str = ordering.toString()
        assertEquals("nameITEMDESCENDINGxINT", str)
    }

    @Test
    fun `OrderingInput defaults all fields to null`() {
        val input = OrderingInput()
        assertNull(input.field)
        assertNull(input.location)
        assertNull(input.order)
        assertNull(input.path)
        assertNull(input.type)
    }

    @Test
    fun `OrderingInput stores all properties`() {
        val input = OrderingInput(
            field = "date",
            location = AttributeLocation.RELATIONSHIP,
            order = Order.DESCENDING,
            path = listOf("created"),
            type = AttributeType.DATE
        )
        assertEquals("date", input.field)
        assertEquals(AttributeLocation.RELATIONSHIP, input.location)
        assertEquals(Order.DESCENDING, input.order)
        assertEquals(AttributeType.DATE, input.type)
    }
}
