package bosca.content.ordering.graphql

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.content.ordering.Order
import bosca.content.ordering.Ordering
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrderingControllerTest {

    private val controller = OrderingController()

    @Test
    fun `field returns ordering field`() {
        val ordering = Ordering(field = "name")

        assertEquals("name", controller.field(ordering))
    }

    @Test
    fun `field returns null when not set`() {
        val ordering = Ordering()

        assertNull(controller.field(ordering))
    }

    @Test
    fun `location returns ordering location`() {
        val ordering = Ordering(location = AttributeLocation.ITEM)

        assertEquals(AttributeLocation.ITEM, controller.location(ordering))
    }

    @Test
    fun `location returns null when not set`() {
        val ordering = Ordering()

        assertNull(controller.location(ordering))
    }

    @Test
    fun `order returns ordering order`() {
        val ordering = Ordering(order = Order.ASCENDING)

        assertEquals(Order.ASCENDING, controller.order(ordering))
    }

    @Test
    fun `order returns null when not set`() {
        val ordering = Ordering()

        assertNull(controller.order(ordering))
    }

    @Test
    fun `path returns ordering path`() {
        val ordering = Ordering(path = listOf("attributes", "priority"))

        assertEquals(listOf("attributes", "priority"), controller.path(ordering))
    }

    @Test
    fun `path returns null when not set`() {
        val ordering = Ordering()

        assertNull(controller.path(ordering))
    }

    @Test
    fun `type returns ordering type`() {
        val ordering = Ordering(type = AttributeType.INT)

        assertEquals(AttributeType.INT, controller.type(ordering))
    }

    @Test
    fun `type returns null when not set`() {
        val ordering = Ordering()

        assertNull(controller.type(ordering))
    }
}
