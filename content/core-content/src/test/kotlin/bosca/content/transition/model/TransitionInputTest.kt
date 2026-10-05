package bosca.content.transition.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TransitionInputTest {

    @Test
    fun `TransitionInput stores all fields`() {
        val config = buildJsonObject { put("timeout", 30) }
        val input = TransitionInput(
            description = "Move to published",
            fromStateId = "draft",
            toStateId = "published",
            enterJobName = "enter-publish",
            exitJobName = "exit-draft",
            configuration = config
        )
        assertEquals("Move to published", input.description)
        assertEquals("draft", input.fromStateId)
        assertEquals("published", input.toStateId)
        assertEquals("enter-publish", input.enterJobName)
        assertEquals("exit-draft", input.exitJobName)
        assertEquals(config, input.configuration)
    }

    @Test
    fun `TransitionInput nullable fields can be null`() {
        val input = TransitionInput(
            description = "desc",
            fromStateId = "a",
            toStateId = "b",
            enterJobName = null,
            exitJobName = null,
            configuration = null
        )
        assertNull(input.enterJobName)
        assertNull(input.exitJobName)
        assertNull(input.configuration)
    }

    @Test
    fun `TransitionInput data class equality`() {
        val input1 = TransitionInput(
            description = "d", fromStateId = "a", toStateId = "b",
            enterJobName = null, exitJobName = null, configuration = null
        )
        val input2 = TransitionInput(
            description = "d", fromStateId = "a", toStateId = "b",
            enterJobName = null, exitJobName = null, configuration = null
        )
        assertEquals(input1, input2)
        assertEquals(input1.hashCode(), input2.hashCode())
    }

    @Test
    fun `TransitionInput inequality on different fromStateId`() {
        val input1 = TransitionInput(
            description = "d", fromStateId = "a", toStateId = "b",
            enterJobName = null, exitJobName = null, configuration = null
        )
        val input2 = TransitionInput(
            description = "d", fromStateId = "c", toStateId = "b",
            enterJobName = null, exitJobName = null, configuration = null
        )
        assertNotEquals(input1, input2)
    }

    @Test
    fun `TransitionInput copy preserves unchanged fields`() {
        val input = TransitionInput(
            description = "desc", fromStateId = "draft", toStateId = "review",
            enterJobName = "enter", exitJobName = "exit", configuration = null
        )
        val copied = input.copy(toStateId = "published")
        assertEquals("published", copied.toStateId)
        assertEquals("draft", copied.fromStateId)
        assertEquals("desc", copied.description)
        assertEquals("enter", copied.enterJobName)
    }
}
