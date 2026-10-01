package bosca.content.transition.graphql

import bosca.content.transition.model.Transition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransitionControllerTest {

    private val controller = TransitionController()

    private fun createTransition(
        fromStateId: String = "draft",
        toStateId: String = "published",
        description: String = "Publish content",
        configuration: JsonElement? = null,
        enterJobName: String? = null,
        exitJobName: String? = null
    ) = Transition(
        fromStateId = fromStateId,
        toStateId = toStateId,
        description = description,
        enterJobName = enterJobName,
        exitJobName = exitJobName,
        configuration = configuration
    )

    @Test
    fun `fromStateId returns transition fromStateId`() {
        assertEquals("draft", controller.fromStateId(createTransition(fromStateId = "draft")))
    }

    @Test
    fun `toStateId returns transition toStateId`() {
        assertEquals("published", controller.toStateId(createTransition(toStateId = "published")))
    }

    @Test
    fun `description returns transition description`() {
        assertEquals("Publish content", controller.description(createTransition(description = "Publish content")))
    }

    @Test
    fun `configuration returns transition configuration`() {
        val config = JsonObject(mapOf("autoApprove" to JsonPrimitive(true)))
        val transition = createTransition(configuration = config)

        assertEquals(config, controller.configuration(transition))
    }

    @Test
    fun `configuration returns null when not set`() {
        assertNull(controller.configuration(createTransition(configuration = null)))
    }

    @Test
    fun `enterJobName returns transition enterJobName`() {
        assertEquals("enter-job", controller.enterJobName(createTransition(enterJobName = "enter-job")))
    }

    @Test
    fun `enterJobName returns null when not set`() {
        assertNull(controller.enterJobName(createTransition(enterJobName = null)))
    }

    @Test
    fun `exitJobName returns transition exitJobName`() {
        assertEquals("exit-job", controller.exitJobName(createTransition(exitJobName = "exit-job")))
    }

    @Test
    fun `exitJobName returns null when not set`() {
        assertNull(controller.exitJobName(createTransition(exitJobName = null)))
    }
}
