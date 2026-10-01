package bosca.pipelines.model

import bosca.pipelines.repository.PipelineShapeRecord
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class PipelineStepModelsCoverageTest {

    @Test
    fun `node group defaults copy equality and serialization contract`() {
        val defaults = NodeGroup("group")
        val complete = NodeGroup(
            id = "group",
            label = "Build",
            x = 1.0,
            y = 2.0,
            width = 3.0,
            height = 4.0,
            collapsed = true,
            color = "red",
            nodeIds = listOf("one"),
        )

        assertEquals("", defaults.label)
        assertEquals(240.0, defaults.width)
        assertEquals(complete, complete.copy())
        assertEquals(complete, complete.copy(id = "group"))
        assertNotEquals(complete, complete.copy(id = "other"))
        assertNotEquals(complete, complete.copy(label = "Other"))
        assertNotEquals(complete, complete.copy(x = 9.0))
        assertNotEquals(complete, complete.copy(y = 9.0))
        assertNotEquals(complete, complete.copy(width = 9.0))
        assertNotEquals(complete, complete.copy(height = 9.0))
        assertNotEquals(complete, complete.copy(collapsed = false))
        assertNotEquals(complete, complete.copy(color = null))
        assertNotEquals(complete, complete.copy(nodeIds = emptyList()))
        assertFalse(complete.equals(null))
        assertFalse(complete.equals("group"))
        assertEquals(complete.hashCode(), complete.copy().hashCode())
        assertEquals(complete.toString(), complete.copy().toString())
    }

    @Test
    fun `run step and awaiting node preserve control targeting metadata`() {
        val runId = UUID.random()
        val defaults = RunStep("node", "Title", RunStepKind.STATUS, RunStepStatus.PENDING)
        val complete = RunStep(
            nodeId = "node",
            title = "Title",
            kind = RunStepKind.HUMAN,
            status = RunStepStatus.WAITING,
            depth = 2,
            item = "api",
            runId = runId,
            type = "gate.approval",
            channelType = "container",
        )

        assertEquals(0, defaults.depth)
        assertEquals(complete, complete.copy())
        assertNotEquals(complete, complete.copy(nodeId = "other"))
        assertNotEquals(complete, complete.copy(title = "Other"))
        assertNotEquals(complete, complete.copy(kind = RunStepKind.STATUS))
        assertNotEquals(complete, complete.copy(status = RunStepStatus.DONE))
        assertNotEquals(complete, complete.copy(depth = 3))
        assertNotEquals(complete, complete.copy(item = null))
        assertNotEquals(complete, complete.copy(runId = null))
        assertNotEquals(complete, complete.copy(type = null))
        assertNotEquals(complete, complete.copy(channelType = null))
        assertFalse(complete.equals(null))
        assertFalse(complete.equals("node"))
        assertEquals(complete.hashCode(), complete.copy().hashCode())
        assertEquals(complete.toString(), complete.copy().toString())

        val awaiting = RunAwaitingNode("gate", "gate.approval", "Approve", runId)
        assertEquals(awaiting, awaiting.copy())
        assertNotEquals(awaiting, awaiting.copy(nodeId = "other"))
        assertNotEquals(awaiting, awaiting.copy(type = "waitForInput"))
        assertNotEquals(awaiting, awaiting.copy(name = "Other"))
        assertNotEquals(awaiting, awaiting.copy(runId = null))
    }

    @Test
    fun `shape record supports explicit and default timestamps`() {
        val now = java.time.OffsetDateTime.now()
        val fields = JsonArray(listOf(JsonPrimitive("field")))
        val explicit = PipelineShapeRecord("shape", fields, now, now)
        val defaults = PipelineShapeRecord("shape", fields)

        assertEquals(explicit, explicit.copy())
        assertNotEquals(explicit, explicit.copy(name = "other"))
        assertNotEquals(explicit, explicit.copy(fields = JsonArray(emptyList())))
        assertNotEquals(explicit, explicit.copy(createdAt = now.minusSeconds(1)))
        assertNotEquals(explicit, explicit.copy(modifiedAt = now.plusSeconds(1)))
        assertEquals("shape", defaults.name)
        assertFalse(explicit.equals(null))
        assertFalse(explicit.equals("shape"))
        assertEquals(explicit.hashCode(), explicit.copy().hashCode())
    }
}
