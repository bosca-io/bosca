package bosca.content.metadata.graphql

import bosca.content.metadata.model.ContainerRenderer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContainerRendererControllerTest {

    private val controller = ContainerRendererController()

    @Test
    fun `name returns renderer name`() {
        val renderer = ContainerRenderer(name = "markdown", configuration = null)

        assertEquals("markdown", controller.name(renderer))
    }

    @Test
    fun `configuration returns renderer configuration`() {
        val config = JsonObject(mapOf("theme" to JsonPrimitive("dark")))
        val renderer = ContainerRenderer(name = "html", configuration = config)

        assertEquals(config, controller.configuration(renderer))
    }

    @Test
    fun `configuration returns null when not set`() {
        val renderer = ContainerRenderer(name = "html", configuration = null)

        assertNull(controller.configuration(renderer))
    }
}
