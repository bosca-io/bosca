package bosca.content.state.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class StateInputTest {

    @Test
    fun `StateInput stores all required fields`() {
        val config = buildJsonObject { put("retry", 3) }
        val input = StateInput(
            id = "state-1",
            name = "Draft",
            description = "Initial draft state",
            type = WorkflowStateType.DRAFT,
            configuration = config
        )
        assertEquals("state-1", input.id)
        assertEquals("Draft", input.name)
        assertEquals("Initial draft state", input.description)
        assertEquals(WorkflowStateType.DRAFT, input.type)
        assertEquals(config, input.configuration)
    }

    @Test
    fun `StateInput jobName defaults to null`() {
        val input = StateInput(
            id = "s1",
            name = "Pending",
            description = "desc",
            type = WorkflowStateType.PENDING,
            configuration = JsonObject(emptyMap())
        )
        assertNull(input.jobName)
    }

    @Test
    fun `StateInput stores jobName when provided`() {
        val input = StateInput(
            id = "s2",
            name = "Processing",
            description = "desc",
            type = WorkflowStateType.PROCESSING,
            configuration = JsonObject(emptyMap()),
            jobName = "process-job"
        )
        assertEquals("process-job", input.jobName)
    }

    @Test
    fun `StateInput data class equality`() {
        val config = JsonObject(emptyMap())
        val input1 = StateInput(id = "s1", name = "n", description = "d", type = WorkflowStateType.DRAFT, configuration = config)
        val input2 = StateInput(id = "s1", name = "n", description = "d", type = WorkflowStateType.DRAFT, configuration = config)
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `StateInput inequality on different type`() {
        val config = JsonObject(emptyMap())
        val input1 = StateInput(id = "s1", name = "n", description = "d", type = WorkflowStateType.DRAFT, configuration = config)
        val input2 = StateInput(id = "s1", name = "n", description = "d", type = WorkflowStateType.PUBLISHED, configuration = config)
        assertNotEquals(input1, input2)
    }

    @Test
    fun `StateInput copy preserves unchanged fields`() {
        val config = buildJsonObject { put("k", "v") }
        val input = StateInput(
            id = "s1", name = "Draft", description = "desc",
            type = WorkflowStateType.DRAFT, configuration = config
        )
        val copied = input.copy(name = "Published", type = WorkflowStateType.PUBLISHED)
        assertEquals("Published", copied.name)
        assertEquals(WorkflowStateType.PUBLISHED, copied.type)
        assertEquals("s1", copied.id)
        assertEquals(config, copied.configuration)
    }
}
