package bosca.content.state.graphql

import bosca.content.state.model.State
import bosca.content.state.model.WorkflowStateType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StateControllerCoverageTest {

    private val controller = StateController()

    private fun createState(
        id: String = "draft",
        name: String = "Draft",
        description: String = "Initial state",
        configuration: JsonElement = JsonObject(emptyMap()),
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
    fun `configuration returns empty configuration`() {
        val configuration = JsonObject(emptyMap())
        val state = createState(configuration = configuration)

        assertSame(configuration, controller.configuration(state))
    }

    @Test
    fun `configuration returns populated configuration`() {
        val configuration = JsonObject(mapOf("enabled" to JsonPrimitive(true)))
        val state = createState(configuration = configuration)

        assertEquals(configuration, controller.configuration(state))
    }
}
