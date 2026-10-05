package bosca.content.transition.graphql

import bosca.content.transition.model.TransitionIdObject
import kotlin.test.Test
import kotlin.test.assertEquals

class TransitionIdObjectControllerTest {

    private val controller = TransitionIdObjectController()

    @Test
    fun `fromStateId returns the fromStateId`() {
        val id = TransitionIdObject(fromStateId = "draft", toStateId = "review")

        assertEquals("draft", controller.fromStateId(id))
    }

    @Test
    fun `toStateId returns the toStateId`() {
        val id = TransitionIdObject(fromStateId = "draft", toStateId = "review")

        assertEquals("review", controller.toStateId(id))
    }
}
