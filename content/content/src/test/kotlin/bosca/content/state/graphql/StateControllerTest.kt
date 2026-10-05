package bosca.content.state.graphql

import bosca.content.state.model.State
import bosca.content.state.model.WorkflowStateType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class StateControllerTest {

    private val controller = StateController()

    private fun createState(
        id: String = "draft",
        name: String = "Draft",
        description: String = "Initial state",
        configuration: JsonObject = JsonObject(emptyMap()),
        type: WorkflowStateType = WorkflowStateType.PROCESSING,
        jobName: String? = null
    ) = State(
        id = id,
        name = name,
        description = description,
        configuration = configuration,
        type = type,
        jobName = jobName
    )

    @Test
    fun `id returns state id`() {
        assertEquals("draft", controller.id(createState(id = "draft")))
    }

    @Test
    fun `name returns state name`() {
        assertEquals("Draft", controller.name(createState(name = "Draft")))
    }

    @Test
    fun `description returns state description`() {
        assertEquals("Initial state", controller.description(createState(description = "Initial state")))
    }

    @Test
    fun `type returns state type`() {
        assertEquals(WorkflowStateType.PROCESSING, controller.type(createState(type = WorkflowStateType.PROCESSING)))
    }

    @Test
    fun `jobName returns state jobName`() {
        assertEquals("process-job", controller.jobName(createState(jobName = "process-job")))
    }

    @Test
    fun `jobName returns null when not set`() {
        assertEquals(null, controller.jobName(createState(jobName = null)))
    }
}
