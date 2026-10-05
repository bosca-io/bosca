package bosca.content.healthcheck

import bosca.content.metadata.model.ContentHealthCheckItem
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit coverage for [ContentHealthCheckItemController].
 *
 * The controller is a pure GraphQL scalar-field resolver over the [ContentHealthCheckItem] data
 * class: each `@Field` method returns one property of the supplied item with no dependencies and
 * no branching. Coverage simply exercises every resolver against a real item instance.
 */
class ContentHealthCheckItemControllerCoverageTest {

    private val controller = ContentHealthCheckItemController()

    private fun item(
        id: UUID = UUID.random(),
        name: String = "item",
        workflowState: String = "draft",
    ): ContentHealthCheckItem = ContentHealthCheckItem(
        id = id,
        name = name,
        workflowState = workflowState,
    )

    @Test
    fun `id resolver returns the item id`() {
        val id = UUID.random()
        val item = item(id = id)
        assertEquals(id, controller.id(item))
    }

    @Test
    fun `name resolver returns the item name`() {
        val item = item(name = "Broken Relationships")
        assertEquals("Broken Relationships", controller.name(item))
    }

    @Test
    fun `workflowState resolver returns the item workflow state`() {
        val item = item(workflowState = "published")
        assertEquals("published", controller.workflowState(item))
    }

    @Test
    fun `resolvers reflect distinct items independently`() {
        val idA = UUID.random()
        val idB = UUID.random()
        val a = item(id = idA, name = "a", workflowState = "draft")
        val b = item(id = idB, name = "b", workflowState = "archived")

        assertEquals(idA, controller.id(a))
        assertEquals("a", controller.name(a))
        assertEquals("draft", controller.workflowState(a))

        assertEquals(idB, controller.id(b))
        assertEquals("b", controller.name(b))
        assertEquals("archived", controller.workflowState(b))
    }
}
