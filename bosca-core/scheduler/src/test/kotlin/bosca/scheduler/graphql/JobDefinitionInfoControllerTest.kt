package bosca.scheduler.graphql

import bosca.scheduler.model.JobDefinitionInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobDefinitionInfoControllerTest {

    private val controller = JobDefinitionInfoController()

    @Test
    fun `id returns definition id`() {
        val info = JobDefinitionInfo(id = "my-job", name = "My Job", queueName = "default")
        assertEquals("my-job", controller.id(info))
    }

    @Test
    fun `name returns definition name`() {
        val info = JobDefinitionInfo(id = "id", name = "My Job", queueName = "default")
        assertEquals("My Job", controller.name(info))
    }

    @Test
    fun `displayName returns display name when set`() {
        val info = JobDefinitionInfo(id = "id", name = "my-job", displayName = "My Job", queueName = "default")
        assertEquals("My Job", controller.displayName(info))
    }

    @Test
    fun `displayName converts kebab-case name to title case when blank`() {
        val info = JobDefinitionInfo(id = "id", name = "my-job", queueName = "default")
        assertEquals("My Job", controller.displayName(info))
    }

    @Test
    fun `displayName converts to title case when displayName equals name`() {
        val info = JobDefinitionInfo(id = "id", name = "my-job", displayName = "my-job", queueName = "default")
        assertEquals("My Job", controller.displayName(info))
    }

    @Test
    fun `queueName returns the queue name`() {
        val info = JobDefinitionInfo(id = "id", name = "name", queueName = "high-priority")
        assertEquals("high-priority", controller.queueName(info))
    }

    @Test
    fun `parameterSchema returns null when not set`() {
        val info = JobDefinitionInfo(id = "id", name = "name", queueName = "q")
        assertNull(controller.parameterSchema(info))
    }

    @Test
    fun `parameterSchema returns schema when set`() {
        val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val info = JobDefinitionInfo(id = "id", name = "name", queueName = "q", parameterSchema = schema)
        assertEquals(schema, controller.parameterSchema(info))
    }
}
