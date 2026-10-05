package bosca.trait.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TraitInputTest {

    @Test
    fun `TraitInput stores all fields`() {
        val input = TraitInput(
            id = "trait-1",
            name = "Featured",
            description = "Marks content as featured",
            deleteWorkflowId = "delete-wf-1",
            workflowIds = listOf("wf-1", "wf-2"),
            contentTypes = listOf("application/json", "text/plain")
        )
        assertEquals("trait-1", input.id)
        assertEquals("Featured", input.name)
        assertEquals("Marks content as featured", input.description)
        assertEquals("delete-wf-1", input.deleteWorkflowId)
        assertEquals(listOf("wf-1", "wf-2"), input.workflowIds)
        assertEquals(listOf("application/json", "text/plain"), input.contentTypes)
    }

    @Test
    fun `TraitInput deleteWorkflowId can be null`() {
        val input = TraitInput(
            id = "t", name = "n", description = "d",
            deleteWorkflowId = null,
            workflowIds = emptyList(),
            contentTypes = emptyList()
        )
        assertNull(input.deleteWorkflowId)
    }

    @Test
    fun `TraitInput with empty lists`() {
        val input = TraitInput(
            id = "t", name = "n", description = "d",
            deleteWorkflowId = null,
            workflowIds = emptyList(),
            contentTypes = emptyList()
        )
        assertEquals(emptyList(), input.workflowIds)
        assertEquals(emptyList(), input.contentTypes)
    }

    @Test
    fun `TraitInput data class equality`() {
        val input1 = TraitInput(
            id = "t1", name = "n", description = "d",
            deleteWorkflowId = null,
            workflowIds = listOf("wf-1"),
            contentTypes = listOf("text/plain")
        )
        val input2 = TraitInput(
            id = "t1", name = "n", description = "d",
            deleteWorkflowId = null,
            workflowIds = listOf("wf-1"),
            contentTypes = listOf("text/plain")
        )
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `TraitInput inequality on different id`() {
        val input1 = TraitInput(
            id = "t1", name = "n", description = "d",
            deleteWorkflowId = null, workflowIds = emptyList(), contentTypes = emptyList()
        )
        val input2 = TraitInput(
            id = "t2", name = "n", description = "d",
            deleteWorkflowId = null, workflowIds = emptyList(), contentTypes = emptyList()
        )
        assertNotEquals(input1, input2)
    }

    @Test
    fun `TraitInput copy preserves unchanged fields`() {
        val input = TraitInput(
            id = "t1", name = "Featured", description = "desc",
            deleteWorkflowId = "del-wf",
            workflowIds = listOf("wf-1"),
            contentTypes = listOf("text/plain")
        )
        val copied = input.copy(name = "Updated")
        assertEquals("Updated", copied.name)
        assertEquals("t1", copied.id)
        assertEquals("desc", copied.description)
        assertEquals("del-wf", copied.deleteWorkflowId)
        assertEquals(listOf("wf-1"), copied.workflowIds)
    }
}
