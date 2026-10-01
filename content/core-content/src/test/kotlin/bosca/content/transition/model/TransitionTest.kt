package bosca.content.transition.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class TransitionTest {

    @Test
    fun `stores all fields`() {
        val config = JsonObject(mapOf("timeout" to JsonPrimitive(60)))
        val transition = Transition(
            fromStateId = "draft",
            toStateId = "published",
            description = "Publish content",
            enterJobName = "enter-publish",
            exitJobName = "exit-draft",
            configuration = config
        )

        assertEquals("draft", transition.fromStateId)
        assertEquals("published", transition.toStateId)
        assertEquals("Publish content", transition.description)
        assertEquals("enter-publish", transition.enterJobName)
        assertEquals("exit-draft", transition.exitJobName)
        assertEquals(config, transition.configuration)
    }

    @Test
    fun `nullable fields default correctly`() {
        val transition = Transition(
            fromStateId = "draft",
            toStateId = "review",
            description = "Send to review",
            enterJobName = null,
            exitJobName = null,
            configuration = null
        )

        assertNull(transition.enterJobName)
        assertNull(transition.exitJobName)
        assertNull(transition.configuration)
    }

    @Test
    fun `data class equality`() {
        val a = Transition(fromStateId = "a", toStateId = "b", description = "d", enterJobName = null, exitJobName = null, configuration = null)
        val b = Transition(fromStateId = "a", toStateId = "b", description = "d", enterJobName = null, exitJobName = null, configuration = null)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality on different description`() {
        val a = Transition(fromStateId = "a", toStateId = "b", description = "desc1", enterJobName = null, exitJobName = null, configuration = null)
        val b = Transition(fromStateId = "a", toStateId = "b", description = "desc2", enterJobName = null, exitJobName = null, configuration = null)
        assertNotEquals(a, b)
    }

    @Test
    fun `copy modifies fields`() {
        val original = Transition(fromStateId = "draft", toStateId = "review", description = "Review", enterJobName = null, exitJobName = null, configuration = null)
        val copied = original.copy(toStateId = "published", description = "Publish", enterJobName = "publish-enter")
        assertEquals("published", copied.toStateId)
        assertEquals("Publish", copied.description)
        assertEquals("publish-enter", copied.enterJobName)
        assertEquals("draft", copied.fromStateId)
    }
}
