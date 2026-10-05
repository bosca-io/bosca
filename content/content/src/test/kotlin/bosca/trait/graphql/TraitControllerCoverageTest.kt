package bosca.trait.graphql

import bosca.trait.model.Trait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TraitControllerCoverageTest {

    private val controller = TraitController()

    @Test
    fun `id returns trait id`() {
        val trait = Trait(id = "trait-1", name = "Trait One", description = "First", deleteWorkflowId = "wf-1")

        assertEquals("trait-1", controller.id(trait))
    }

    @Test
    fun `name returns trait name`() {
        val trait = Trait(id = "trait-1", name = "Trait One", description = "First", deleteWorkflowId = "wf-1")

        assertEquals("Trait One", controller.name(trait))
    }

    @Test
    fun `description returns trait description`() {
        val trait = Trait(id = "trait-1", name = "Trait One", description = "First", deleteWorkflowId = "wf-1")

        assertEquals("First", controller.description(trait))
    }

    @Test
    fun `deleteWorkflowId returns workflow id when present`() {
        val trait = Trait(id = "trait-1", name = "Trait One", description = "First", deleteWorkflowId = "wf-1")

        assertEquals("wf-1", controller.deleteWorkflowId(trait))
    }

    @Test
    fun `deleteWorkflowId returns null when absent`() {
        val trait = Trait(id = "trait-2", name = "Trait Two", description = "Second", deleteWorkflowId = null)

        assertNull(controller.deleteWorkflowId(trait))
    }
}
