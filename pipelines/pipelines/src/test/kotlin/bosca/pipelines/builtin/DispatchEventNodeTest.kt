package bosca.pipelines.builtin

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.testutil.executeForTest
import bosca.pipelines.trigger.PipelineDispatchJob
import bosca.security.service.AuthenticationContext
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DispatchEventNodeTest {

    @Serializable
    private data class Person(val name: String, val email: String)

    private val eventName = "bosca.example.PersonCreated"
    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)
    private val input = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
    private val inputs get() = NodeInputs(mapOf("in" to input))

    /** The generated `enqueue()` resolves the pipelines queue by name and serializes the job via Json. */
    private fun registerQueue(): JobQueue {
        val queue = mockk<JobQueue>(relaxed = true)
        provides<Json> { Json }
        provides<JobQueue>(name = "pipelinesJobQueue") { queue }
        return queue
    }

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `dispatches a pipeline event carrying the inbound value as the payload`() = runTest {
        val queue = registerQueue()
        val node = DispatchEventNode(id = "disp", eventName = eventName)

        val result = node.executeForTest(context, inputs)

        assertNull(result, "Dispatch Event is fire-and-forget — no output")
        val jobSlot = slot<Job>()
        coVerify { queue.enqueue(capture(jobSlot)) }
        val dispatched = Json.decodeFromJsonElement(PipelineDispatchJob.serializer(), jobSlot.captured.getDefinition())
        assertEquals(eventName, dispatched.eventName)
        assertEquals(input.encode(Json), dispatched.eventPayload)
    }

    @Test
    fun `dispatches an empty object payload when no value is connected`() = runTest {
        val queue = registerQueue()
        val node = DispatchEventNode(id = "disp", eventName = eventName)

        node.executeForTest(context, NodeInputs(emptyMap()))

        val jobSlot = slot<Job>()
        coVerify { queue.enqueue(capture(jobSlot)) }
        val dispatched = Json.decodeFromJsonElement(PipelineDispatchJob.serializer(), jobSlot.captured.getDefinition())
        assertEquals(eventName, dispatched.eventName)
        assertEquals(buildJsonObject { }, dispatched.eventPayload)
    }

    @Test
    fun `dry run records the would-be dispatch and enqueues nothing`() = runTest {
        val queue = registerQueue()
        val trace = DryRunTrace()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, trace = trace)
        val node = DispatchEventNode(id = "disp", eventName = eventName)

        val result = node.executeForTest(dry, inputs)

        assertNull(result, "a fire-and-forget action is a sink even in dry runs")
        val action = trace.actions["disp"] as JsonObject
        assertEquals(JsonPrimitive("dispatchEvent"), action["action"])
        assertEquals(JsonPrimitive(eventName), action["eventName"])
        assertEquals(input.encode(Json), action["payload"])
        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `dry run without a trace remains side-effect free`() = runTest {
        val queue = registerQueue()
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true)

        val result = DispatchEventNode(id = "disp", eventName = eventName).executeForTest(dry, inputs)

        assertNull(result)
        coVerify(exactly = 0) { queue.enqueue(any()) }
    }

    @Test
    fun `decoding JSON missing the required eventName throws a serialization exception`() = runTest {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(DispatchEventNode.serializer(), """{"id":"disp"}""")
        }
    }

    @Test
    fun `constructs from only the required id and eventName and reads every defaulted field`() = runTest {
        val node = DispatchEventNode(id = "disp", eventName = eventName)
        assertEquals("disp", node.id)
        assertEquals(eventName, node.eventName)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `round-trips through its serializer with every field present`() = runTest {
        val decoded = Json.decodeFromString(
            DispatchEventNode.serializer(),
            """{"id":"disp","name":"Emit","description":"d","eventName":"$eventName"}""",
        )
        assertEquals("disp", decoded.id)
        assertEquals("Emit", decoded.name)
        assertEquals("d", decoded.description)
        assertEquals(eventName, decoded.eventName)
    }
}
