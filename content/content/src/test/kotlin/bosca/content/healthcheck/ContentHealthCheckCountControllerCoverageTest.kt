package bosca.content.healthcheck

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage for [ContentHealthCheckCountController].
 *
 * The controller is a pure GraphQL scalar-field resolver over the [ContentHealthCheckCount] data
 * class: the single `@Field` method returns the count property of the supplied item with no
 * dependencies and no branching. Coverage exercises the resolver against real item instances.
 */
class ContentHealthCheckCountControllerCoverageTest {

    private val controller = ContentHealthCheckCountController()

    @Test
    fun `count resolver returns the item count`() {
        val item = ContentHealthCheckCount(count = 42)
        assertEquals(42, controller.count(item))
    }

    @Test
    fun `count resolver returns zero for empty count`() {
        val item = ContentHealthCheckCount(count = 0)
        assertEquals(0, controller.count(item))
    }

    @Test
    fun `count resolver reflects distinct items independently`() {
        val a = ContentHealthCheckCount(count = 7)
        val b = ContentHealthCheckCount(count = 13)

        assertEquals(7, controller.count(a))
        assertEquals(13, controller.count(b))
    }
}
