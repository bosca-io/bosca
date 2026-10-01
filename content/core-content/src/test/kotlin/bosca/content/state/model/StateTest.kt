package bosca.content.state.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StateTest {

    @Test
    fun `State stores all required fields`() {
        val config = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val state = State(
            id = "state-1",
            name = "Draft",
            description = "Initial draft state",
            type = WorkflowStateType.DRAFT,
            configuration = config
        )
        assertEquals("state-1", state.id)
        assertEquals("Draft", state.name)
        assertEquals("Initial draft state", state.description)
        assertEquals(WorkflowStateType.DRAFT, state.type)
        assertEquals(config, state.configuration)
    }

    @Test
    fun `State jobName defaults to null`() {
        val state = State(
            id = "s1",
            name = "Pending",
            description = "Pending state",
            type = WorkflowStateType.PENDING,
            configuration = JsonObject(emptyMap())
        )
        assertNull(state.jobName)
    }

    @Test
    fun `State stores jobName when provided`() {
        val state = State(
            id = "s2",
            name = "Processing",
            description = "Processing state",
            type = WorkflowStateType.PROCESSING,
            configuration = JsonObject(emptyMap()),
            jobName = "process-job"
        )
        assertEquals("process-job", state.jobName)
    }

    @Test
    fun `State equality`() {
        val config = JsonObject(emptyMap())
        val state1 = State(id = "s1", name = "Draft", description = "desc", type = WorkflowStateType.DRAFT, configuration = config)
        val state2 = State(id = "s1", name = "Draft", description = "desc", type = WorkflowStateType.DRAFT, configuration = config)
        assertEquals(state1, state2)
    }

    @Test
    fun `State copy`() {
        val config = JsonObject(emptyMap())
        val state = State(id = "s1", name = "Draft", description = "desc", type = WorkflowStateType.DRAFT, configuration = config)
        val copied = state.copy(name = "Published", type = WorkflowStateType.PUBLISHED)
        assertEquals("Published", copied.name)
        assertEquals(WorkflowStateType.PUBLISHED, copied.type)
        assertEquals("s1", copied.id)
    }
}
