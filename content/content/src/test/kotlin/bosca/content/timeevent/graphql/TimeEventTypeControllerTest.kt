package bosca.content.timeevent.graphql

import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.service.TimeEventService
import io.mockk.clearAllMocks
import io.mockk.mockk
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeEventTypeControllerTest {

    private val timeEventService = mockk<TimeEventService>()
    private val controller = TimeEventTypeController(timeEventService)

    @AfterTest
    fun tearDown() {
        clearAllMocks()
    }

    private fun createType(
        id: String = "verse",
        name: String = "Verse",
        description: String = "Bible verse event",
        schema: JsonElement? = null,
        configuration: JsonElement? = null
    ) = TimeEventType(
        id = id,
        name = name,
        description = description,
        schema = schema,
        configuration = configuration
    )

    @Test
    fun `id returns type id`() {
        assertEquals("verse", controller.id(createType(id = "verse")))
    }

    @Test
    fun `name returns type name`() {
        assertEquals("Verse", controller.name(createType(name = "Verse")))
    }

    @Test
    fun `description returns type description`() {
        assertEquals("Bible verse event", controller.description(createType(description = "Bible verse event")))
    }

    @Test
    fun `schema returns type schema`() {
        val schemaObj = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val type = createType(schema = schemaObj)

        assertEquals(schemaObj, controller.schema(type))
    }

    @Test
    fun `schema returns null when not set`() {
        assertNull(controller.schema(createType(schema = null)))
    }

    @Test
    fun `configuration returns type configuration`() {
        val config = JsonObject(mapOf("color" to JsonPrimitive("blue")))
        val type = createType(configuration = config)

        assertEquals(config, controller.configuration(type))
    }

    @Test
    fun `configuration returns null when not set`() {
        assertNull(controller.configuration(createType(configuration = null)))
    }
}
