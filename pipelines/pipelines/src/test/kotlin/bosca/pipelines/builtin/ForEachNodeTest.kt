@file:OptIn(Internal::class)

package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTestValue

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.pipelines.service.PipelineExecutor
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.pipelines.model.Pipeline as ModelPipeline
import bosca.pipelines.service.ExecutionResult
import bosca.pipelines.service.ExecutionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ForEachNodeTest {

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)

    /** Body node tracking peak concurrency: yields so co-permitted items actually interleave. */
    private class ConcurrencyProbeNode(
        override val id: String,
        private val current: AtomicInteger,
        private val peak: AtomicInteger,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
            val c = current.incrementAndGet()
            peak.updateAndGet { maxOf(it, c) }
            yield()
            current.decrementAndGet()
            return inputs.first
        }
    }

    private class FailOnNode(
        override val id: String,
        private val failWhen: (JsonElement) -> Boolean,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
            val v = inputs.first?.encode(context.json) ?: error("no input")
            if (failWhen(v)) error("boom")
            return inputs.first
        }
    }

    private class FakePipelineService(private val pipelines: Map<UUID, Pipeline>) : PipelineService {
        override suspend fun getAll(): List<Pipeline> = pipelines.values.toList()
        override suspend fun getBroken(): List<bosca.pipelines.model.BrokenPipeline> = emptyList()
        override suspend fun get(id: UUID): Pipeline? = pipelines[id]
        override suspend fun getByKey(key: String): Pipeline? = pipelines.values.firstOrNull { it.key == key }
        override suspend fun acceptingInput(typeNames: Set<String>): List<Pipeline> = error("unused")
        override suspend fun triggeredFor(eventName: String): List<Pipeline> = error("unused")
        override suspend fun triggeredEventTypes(): Set<String> = error("unused")
        override suspend fun run(pipeline: Pipeline, input: PipelineValue): PipelineValue? = error("unused")
        override suspend fun save(
            id: UUID, name: String, description: String, acceptedInputType: String, triggered: Boolean,
            version: Long, graph: JsonElement, tags: List<String>, key: String, api: Boolean, public: Boolean,
            schedule: String?, maxConcurrentRuns: Int?, maxRunsPerMinute: Int?,
        ): Pipeline = error("unused")
        override suspend fun delete(id: UUID) = error("unused")
        override suspend fun graphAsJsonElement(pipeline: Pipeline): JsonElement = error("unused")
        override suspend fun decodeGraph(graph: JsonElement): bosca.pipelines.model.PipelineGraph = error("unused")
        override suspend fun descriptorFor(node: PipelineNode): bosca.pipelines.node.NodeDescriptor? = error("unused")
        override suspend fun validateGraph(graph: JsonElement): String? = error("unused")
        override suspend fun getPermissions(entity: Pipeline): List<bosca.security.model.EntityPermission> = emptyList()
        override suspend fun addPermissionsToBatch(batch: bosca.graphql.Batch<UUID, List<bosca.security.model.EntityPermission>>) = error("unused")
        override suspend fun addPermission(pipelineId: UUID, groupId: UUID, action: bosca.security.model.PermissionAction) = error("unused")
        override suspend fun deletePermission(pipelineId: UUID, groupId: UUID, action: bosca.security.model.PermissionAction) = error("unused")
    }

    private fun register(vararg pipelines: Pipeline) {
        provides<PipelineService> { FakePipelineService(pipelines.associateBy { it.id }) }
        provides<PipelineExecutor> { PipelineExecutorImpl() }
    }

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    /** A body pipeline that extracts field `n` from each item ({n: …} -> …). */
    private fun extractNBody(id: UUID) = Pipeline(
        id = id, name = "body", acceptedInputType = "JSON",
        nodes = listOf(InputNode(id = "in", acceptedType = "JSON"), JsonataNode(id = "jx", expression = "n"), OutputNode(id = "out")),
        edges = listOf(PipelineEdge("e1", "in", "jx"), PipelineEdge("e2", "jx", "out")),
    )

    private fun items(vararg n: Int) = JsonArray(n.map { buildJsonObject { put("n", it) } })

    @Test
    fun `maps the body over each item and joins outputs in input order`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val node = ForEach(id = "each", pipelineId = bodyId)
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1, 2, 3)))))
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2), JsonPrimitive(3))), result.value)
    }

    @Test
    fun `results stay in input order even with concurrency`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val node = ForEach(id = "each", pipelineId = bodyId, maxConcurrency = 4)
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(0, 1, 2, 3, 4)))))
        assertEquals((0..4).map { JsonPrimitive(it) }, (result.value as JsonArray).toList())
    }

    @Test
    fun `concurrency is bounded by maxConcurrency`() = runTest {
        val current = AtomicInteger()
        val peak = AtomicInteger()
        val bodyId = Uuid.random()
        val body = Pipeline(
            id = bodyId, name = "probe", acceptedInputType = "JSON",
            nodes = listOf(InputNode(id = "in", acceptedType = "JSON"), ConcurrencyProbeNode("p", current, peak), OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "p"), PipelineEdge("e2", "p", "out")),
        )
        register(body)
        ForEach(id = "each", pipelineId = bodyId, maxConcurrency = 2)
            .executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1, 2, 3, 4)))))
        assertTrue(peak.get() <= 2, "peak concurrency ${peak.get()} must not exceed the cap of 2")
        assertTrue(peak.get() >= 2, "with 4 items and a cap of 2, two should actually run together")
    }

    @Test
    fun `maxConcurrency of one runs sequentially`() = runTest {
        val current = AtomicInteger()
        val peak = AtomicInteger()
        val bodyId = Uuid.random()
        val body = Pipeline(
            id = bodyId, name = "probe", acceptedInputType = "JSON",
            nodes = listOf(InputNode(id = "in", acceptedType = "JSON"), ConcurrencyProbeNode("p", current, peak), OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "p"), PipelineEdge("e2", "p", "out")),
        )
        register(body)
        ForEach(id = "each", pipelineId = bodyId, maxConcurrency = 1)
            .executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1, 2, 3)))))
        assertEquals(1, peak.get(), "a cap of 1 must run items one at a time")
    }

    @Test
    fun `an empty collection runs nothing and outputs an empty array`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val node = ForEach(id = "each", pipelineId = bodyId)
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonArray(emptyList())))))
        assertEquals(JsonArray(emptyList()), result.value)
    }

    @Test
    fun `itemsField resolves a nested array`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val node = ForEach(id = "each", pipelineId = bodyId, itemsField = "order.lines")
        val input = buildJsonObject { putJsonObject("order") { put("lines", items(7, 8)) } }
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(input))))
        assertEquals(listOf(JsonPrimitive(7), JsonPrimitive(8)), (result.value as JsonArray).toList())
    }

    @Test
    fun `fail-fast fails the whole run on the first bad item`() = runTest {
        val bodyId = Uuid.random()
        val body = Pipeline(
            id = bodyId, name = "body", acceptedInputType = "JSON",
            nodes = listOf(InputNode(id = "in", acceptedType = "JSON"), FailOnNode("f", { (it as JsonObject)["n"] == JsonPrimitive(2) }), OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "f"), PipelineEdge("e2", "f", "out")),
        )
        register(body)
        val node = ForEach(id = "each", pipelineId = bodyId) // continueOnError = false
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1, 2, 3)))))
        }
        assertTrue("item 1" in (failure.message ?: ""), "the failure should name the failing item index, got: ${failure.message}")
    }

    @Test
    fun `continueOnError collects per-item failures as error markers in place`() = runTest {
        val bodyId = Uuid.random()
        val body = Pipeline(
            id = bodyId, name = "body", acceptedInputType = "JSON",
            nodes = listOf(InputNode(id = "in", acceptedType = "JSON"), FailOnNode("f", { (it as JsonObject)["n"] == JsonPrimitive(2) }), OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "f"), PipelineEdge("e2", "f", "out")),
        )
        register(body)
        val node = ForEach(id = "each", pipelineId = bodyId, continueOnError = true)
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1, 2, 3))))).value as JsonArray
        assertEquals(3, result.size)
        // index 0 and 2 succeed (echo their {n}); index 1 is an error marker keyed by index.
        assertEquals(JsonPrimitive(1), result[0].jsonObject["n"])
        assertEquals(JsonPrimitive(1), result[1].jsonObject["index"], "the failed item's marker records its index")
        assertTrue(result[1].jsonObject.containsKey("error"))
        assertEquals(JsonPrimitive(3), result[2].jsonObject["n"])
    }

    // ----- execute(): remaining guard + trace arms -----

    @Test
    fun `execute with no input fails clearly naming the node`() = runTest {
        val node = ForEach(id = "each", name = "loop", pipelineId = Uuid.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(emptyMap()))
        }
        assertTrue("requires an input" in (failure.message ?: ""), "got: ${failure.message}")
        assertTrue("loop" in (failure.message ?: ""), "the name should appear when set: ${failure.message}")
    }

    @Test
    fun `execute with no input and a blank name falls back to the id in the error`() = runTest {
        val node = ForEach(id = "each", pipelineId = Uuid.random()) // name blank -> ifBlank uses id
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(emptyMap()))
        }
        assertTrue("'each'" in (failure.message ?: ""), "the blank name must fall back to the id: ${failure.message}")
    }

    @Test
    fun `execute with no body pipeline selected fails clearly`() = runTest {
        val node = ForEach(id = "each", pipelineId = null) // no body
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1)))))
        }
        assertTrue("no body pipeline selected" in (failure.message ?: ""), "got: ${failure.message}")
    }

    @Test
    fun `execute when the body pipeline is not found fails clearly with the id`() = runTest {
        val missing = Uuid.random()
        register() // no pipelines registered -> get(missing) returns null
        val node = ForEach(id = "each", pipelineId = missing)
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1)))))
        }
        assertTrue("body pipeline not found" in (failure.message ?: ""), "got: ${failure.message}")
        assertTrue(missing.toString() in (failure.message ?: ""), "the missing id should appear: ${failure.message}")
    }

    @Test
    fun `execute records a trace action describing the iteration`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val trace = DryRunTrace()
        val tracingContext = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val node = ForEach(id = "each", pipelineId = bodyId, maxConcurrency = 3)
        node.executeForTestValue(tracingContext, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1, 2)))))
        val action = trace.actions["each"]?.jsonObject ?: error("the forEach action must be traced")
        assertEquals(JsonPrimitive("forEach"), action["action"])
        assertEquals(JsonPrimitive("body"), action["pipelineName"])
        assertEquals(JsonPrimitive(2), action["items"])
        assertEquals(JsonPrimitive(3), action["maxConcurrency"])
    }

    @Test
    fun `execute coerces a non-positive maxConcurrency up to one in the trace`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val trace = DryRunTrace()
        val tracingContext = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        // maxConcurrency 0 must coerce to 1 both for the gate and the recorded trace value.
        val node = ForEach(id = "each", pipelineId = bodyId, maxConcurrency = 0)
        val result = node.executeForTestValue(tracingContext, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(5, 6)))))
        assertEquals(JsonPrimitive(1), trace.actions["each"]?.jsonObject?.get("maxConcurrency"))
        assertEquals(listOf(JsonPrimitive(5), JsonPrimitive(6)), (result.value as JsonArray).toList())
    }

    @Test
    fun `execute with a single item runs the body exactly once`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val node = ForEach(id = "each", pipelineId = bodyId)
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(42)))))
        assertEquals(listOf(JsonPrimitive(42)), (result.value as JsonArray).toList())
    }

    // ----- resolveItems()/navigate(): the non-array / null / absent arms -----

    @Test
    fun `a JSON null input resolves to an empty array`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val node = ForEach(id = "each", pipelineId = bodyId)
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonNull))))
        assertEquals(JsonArray(emptyList()), result.value)
    }

    @Test
    fun `an absent itemsField path resolves to an empty array`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        // The path navigates into an object that lacks the key -> navigate returns null -> emptyList.
        val node = ForEach(id = "each", pipelineId = bodyId, itemsField = "order.lines")
        val input = buildJsonObject { putJsonObject("order") { put("other", 1) } }
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(input))))
        assertEquals(JsonArray(emptyList()), result.value)
    }

    @Test
    fun `navigating through a non-object segment resolves to an empty array`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        // "order" is a primitive, not an object, so the cast to JsonObject yields null mid-path.
        val node = ForEach(id = "each", pipelineId = bodyId, itemsField = "order.lines")
        val input = buildJsonObject { put("order", 7) }
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(input))))
        assertEquals(JsonArray(emptyList()), result.value)
    }

    @Test
    fun `a non-array input (no itemsField) fails naming input`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val node = ForEach(id = "each", pipelineId = bodyId) // itemsField blank
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("not-an-array")))))
        }
        assertTrue("is not an array" in (failure.message ?: ""), "got: ${failure.message}")
        assertTrue("'input'" in (failure.message ?: ""), "blank itemsField must read as 'input': ${failure.message}")
    }

    @Test
    fun `a non-array itemsField value fails naming the field`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val node = ForEach(id = "each", pipelineId = bodyId, itemsField = "order.count")
        val input = buildJsonObject { putJsonObject("order") { put("count", 5) } }
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(input))))
        }
        assertTrue("is not an array" in (failure.message ?: ""), "got: ${failure.message}")
        assertTrue("order.count" in (failure.message ?: ""), "the field path must appear: ${failure.message}")
    }

    // ----- run(): dry / non-durable synchronous fallback -----

    @Test
    fun `run in dry mode takes the synchronous execute path and outputs the mapped array`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val dry = PipelineContext(AuthenticationContext(null, null), Json, dryRun = true, runId = UUID.random())
        val node = ForEach(id = "each", pipelineId = bodyId)
        val result = node.run(dry, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1, 2)))))
        val output = assertIs<NodeResult.Output>(result)
        assertEquals(listOf(JsonPrimitive(1), JsonPrimitive(2)), (output.value?.value as JsonArray).toList())
    }

    @Test
    fun `run without a runId takes the synchronous execute path`() = runTest {
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        // context has runId == null (and dryRun == false) -> synchronous fallback, no PipelineRunService needed.
        val node = ForEach(id = "each", pipelineId = bodyId)
        val result = node.run(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(9)))))
        val output = assertIs<NodeResult.Output>(result)
        assertEquals(listOf(JsonPrimitive(9)), (output.value?.value as JsonArray).toList())
    }

    // ----- run(): durable suspend path -----

    @Test
    fun `a durable run with items suspends on the iteration await and enqueues startIteration`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        coEvery { runService.startIteration(any(), any(), any(), any(), any(), any()) } returns Unit
        provides<PipelineRunService> { runService }

        val bodyId = Uuid.random()
        val runId = UUID.random()
        val created = OffsetDateTime.now()
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = runId, inputCreated = created)
        val node = ForEach(id = "each", pipelineId = bodyId, continueOnError = true)

        val result = node.run(durable, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1, 2, 3)))))
        val suspended = assertIs<NodeResult.Suspend>(result)

        // The enqueue thunk must not have fired yet — the executor runs it after persisting the checkpoint.
        coVerify(exactly = 0) { runService.startIteration(any(), any(), any(), any(), any(), any()) }

        suspended.enqueue()
        coVerify(exactly = 1) {
            runService.startIteration(
                runId,
                "each",
                bodyId,
                match { it.size == 3 },
                true, // continueOnError threaded through
                created,
                null,
                null,
                1, // the node's maxConcurrency (default 1) threaded through — bounds the durable fan-out
            )
        }
    }

    @Test
    fun `a durable run with an empty collection outputs an empty array without suspending`() = runTest {
        val runService = mockk<PipelineRunService>(relaxed = true)
        provides<PipelineRunService> { runService }
        val node = ForEach(id = "each", pipelineId = Uuid.random())
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val result = node.run(durable, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonArray(emptyList())))))
        val output = assertIs<NodeResult.Output>(result)
        assertEquals(JsonArray(emptyList()), output.value?.value)
        coVerify(exactly = 0) { runService.startIteration(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a durable run with no input fails clearly before suspending`() = runTest {
        val node = ForEach(id = "each", name = "iter", pipelineId = Uuid.random())
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.run(durable, NodeInputs(emptyMap()))
        }
        assertTrue("requires an input" in (failure.message ?: ""), "got: ${failure.message}")
        assertTrue("iter" in (failure.message ?: ""), "the name should appear: ${failure.message}")
    }

    @Test
    fun `a durable run with no body pipeline fails clearly before suspending`() = runTest {
        val node = ForEach(id = "each", pipelineId = null)
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.run(durable, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1)))))
        }
        assertTrue("no body pipeline selected" in (failure.message ?: ""), "got: ${failure.message}")
        // The blank name falls back to the id for the durable run() path too.
        assertTrue("'each'" in (failure.message ?: ""), "the blank name must fall back to the id: ${failure.message}")
    }

    @Test
    fun `a durable run with a non-array input fails the items guard`() = runTest {
        val node = ForEach(id = "each", pipelineId = Uuid.random())
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.run(durable, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("nope")))))
        }
        assertTrue("is not an array" in (failure.message ?: ""), "got: ${failure.message}")
    }

    // ----- @Serializable contract -----

    @Test
    fun `round-trips through JSON preserving every field`() = runTest {
        val json = Json
        val pid = Uuid.random()
        val node = ForEach(
            id = "each",
            name = "loop",
            description = "maps",
            pipelineId = pid,
            itemsField = "order.lines",
            maxConcurrency = 4,
            continueOnError = true,
        )
        val encoded = json.encodeToString(ForEach.serializer(), node)
        val decoded = json.decodeFromString(ForEach.serializer(), encoded)
        assertEquals(node.id, decoded.id)
        assertEquals(node.name, decoded.name)
        assertEquals(node.description, decoded.description)
        assertEquals(pid, decoded.pipelineId)
        assertEquals("order.lines", decoded.itemsField)
        assertEquals(4, decoded.maxConcurrency)
        assertTrue(decoded.continueOnError)
    }

    @Test
    fun `decodes from minimal JSON applying every field default`() = runTest {
        val decoded = Json.decodeFromString(ForEach.serializer(), """{"id":"each"}""")
        assertEquals("each", decoded.id)
        assertEquals("", decoded.name)
        assertEquals("", decoded.description)
        assertNull(decoded.pipelineId)
        assertEquals("", decoded.itemsField)
        assertEquals(1, decoded.maxConcurrency)
        assertFalse(decoded.continueOnError)
    }

    // ----- synthetic default-args constructor: distinct PARTIAL subsets of the optional args -----
    // Each construction leaves a different combination of optional parameters at default, exercising
    // the <init>$default mask branches the all-args / minimal-args tests above do not reach.

    @Test
    fun `constructs with only id and reads every defaulted field`() = runTest {
        val node = ForEach(id = "each")
        assertEquals("each", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertNull(node.pipelineId)
        assertEquals("", node.itemsField)
        assertEquals(1, node.maxConcurrency)
        assertFalse(node.continueOnError)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `constructs with id plus name leaving the rest default`() = runTest {
        val node = ForEach(id = "each", name = "loop")
        assertEquals("loop", node.name)
        assertEquals("", node.description)
        assertNull(node.pipelineId)
        assertEquals("", node.itemsField)
        assertEquals(1, node.maxConcurrency)
        assertFalse(node.continueOnError)
    }

    @Test
    fun `constructs with id plus description leaving the rest default`() = runTest {
        val node = ForEach(id = "each", description = "maps over a collection")
        assertEquals("", node.name)
        assertEquals("maps over a collection", node.description)
        assertNull(node.pipelineId)
        assertEquals(1, node.maxConcurrency)
    }

    @Test
    fun `constructs with id plus itemsField leaving the rest default`() = runTest {
        val node = ForEach(id = "each", itemsField = "order.lines")
        assertEquals("order.lines", node.itemsField)
        assertEquals("", node.name)
        assertNull(node.pipelineId)
        assertEquals(1, node.maxConcurrency)
        assertFalse(node.continueOnError)
    }

    @Test
    fun `constructs with id plus maxConcurrency leaving the rest default`() = runTest {
        val node = ForEach(id = "each", maxConcurrency = 8)
        assertEquals(8, node.maxConcurrency)
        assertEquals("", node.itemsField)
        assertFalse(node.continueOnError)
    }

    @Test
    fun `constructs with id plus continueOnError leaving the rest default`() = runTest {
        val node = ForEach(id = "each", continueOnError = true)
        assertTrue(node.continueOnError)
        assertEquals(1, node.maxConcurrency)
        assertEquals("", node.itemsField)
    }

    @Test
    fun `constructs with an explicit position leaving the rest default`() = runTest {
        val node = ForEach(id = "each", position = NodePosition(x = 11.0, y = 22.0))
        assertEquals(NodePosition(x = 11.0, y = 22.0), node.position)
        assertEquals(11.0, node.position.x)
        assertEquals(22.0, node.position.y)
        assertEquals("", node.name)
        assertNull(node.pipelineId)
        assertEquals(1, node.maxConcurrency)
    }

    // ----- @Serializable: the missing-required-field throw arm of the generated deserialization
    // constructor (line 51 header). Decoding JSON without the required `id` must take the
    // throwMissingFieldException arm the synthetic constructor's mask check guards. -----

    @Test
    fun `decoding JSON missing the required id throws a serialization exception`() = runTest {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(ForEach.serializer(), """{"itemsField":"x"}""")
        }
    }

    // ----- run()/execute()/resolveItems()/runItem(): the OTHER `name.ifBlank { id }` arm of each error.
    // The existing tests cover only one side of the isBlank branch per error message; these add the
    // complement so both arms are taken. -----

    @Test
    fun `a durable run with no input and a blank name falls back to the id`() = runTest {
        // run() requires-input error with a BLANK name -> the ifBlank-true (use id) arm of line 84.
        val node = ForEach(id = "each", pipelineId = Uuid.random()) // name blank
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.run(durable, NodeInputs(emptyMap()))
        }
        assertTrue("requires an input" in (failure.message ?: ""), "got: ${failure.message}")
        assertTrue("'each'" in (failure.message ?: ""), "the blank name must fall back to the id: ${failure.message}")
    }

    @Test
    fun `a durable run with no body pipeline and a name set uses the name`() = runTest {
        // run() no-body error with a name SET -> the ifBlank-false (use name) arm of line 85.
        val node = ForEach(id = "each", name = "Looper", pipelineId = null)
        val durable = PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.run(durable, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1)))))
        }
        assertTrue("no body pipeline selected" in (failure.message ?: ""), "got: ${failure.message}")
        assertTrue("Looper" in (failure.message ?: ""), "the name should appear when set: ${failure.message}")
    }

    @Test
    fun `execute with no body pipeline and a name set uses the name`() = runTest {
        // execute() no-body error with a name SET -> the ifBlank-false (use name) arm of line 97.
        val node = ForEach(id = "each", name = "Looper", pipelineId = null)
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1)))))
        }
        assertTrue("no body pipeline selected" in (failure.message ?: ""), "got: ${failure.message}")
        assertTrue("Looper" in (failure.message ?: ""), "the name should appear when set: ${failure.message}")
    }

    @Test
    fun `execute when the body pipeline is not found and a name set uses the name`() = runTest {
        // execute() not-found error with a name SET -> the ifBlank-false (use name) arm of line 99.
        val missing = Uuid.random()
        register() // no pipelines -> get(missing) returns null
        val node = ForEach(id = "each", name = "Looper", pipelineId = missing)
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1)))))
        }
        assertTrue("body pipeline not found" in (failure.message ?: ""), "got: ${failure.message}")
        assertTrue("Looper" in (failure.message ?: ""), "the name should appear when set: ${failure.message}")
    }

    @Test
    fun `a non-array input with a name set fails naming the node`() = runTest {
        // resolveItems() not-an-array error with a name SET -> the ifBlank-false (use name) arm of line 161.
        val bodyId = Uuid.random()
        register(extractNBody(bodyId))
        val node = ForEach(id = "each", name = "Looper", pipelineId = bodyId)
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(JsonPrimitive("not-an-array")))))
        }
        assertTrue("is not an array" in (failure.message ?: ""), "got: ${failure.message}")
        assertTrue("Looper" in (failure.message ?: ""), "the name should appear when set: ${failure.message}")
    }

    @Test
    fun `fail-fast with a name set names the node in the failure`() = runTest {
        // runItem() fail-fast throw with a name SET -> the ifBlank-false (use name) arm of line 145.
        val bodyId = Uuid.random()
        val body = Pipeline(
            id = bodyId, name = "body", acceptedInputType = "JSON",
            nodes = listOf(InputNode(id = "in", acceptedType = "JSON"), FailOnNode("f", { (it as JsonObject)["n"] == JsonPrimitive(2) }), OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "f"), PipelineEdge("e2", "f", "out")),
        )
        register(body)
        val node = ForEach(id = "each", name = "Looper", pipelineId = bodyId) // continueOnError = false
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1, 2, 3)))))
        }
        assertTrue("Looper" in (failure.message ?: ""), "the name should appear when set: ${failure.message}")
        assertTrue("item 1" in (failure.message ?: ""), "the failing index should still be named: ${failure.message}")
    }

    // ----- runItem(): the `e.message ?: e.toString()` elvis (line 148). A continueOnError item whose
    // exception carries a NULL message must fall through to e.toString() in the error marker. -----

    @Test
    fun `continueOnError records the exception toString when the message is null`() = runTest {
        val bodyId = Uuid.random()
        val body = Pipeline(
            id = bodyId, name = "body", acceptedInputType = "JSON",
            nodes = listOf(InputNode(id = "in", acceptedType = "JSON"), NullMessageFailNode("f"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "f"), PipelineEdge("e2", "f", "out")),
        )
        register(body)
        val node = ForEach(id = "each", pipelineId = bodyId, continueOnError = true)
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1))))).value as JsonArray
        assertEquals(1, result.size)
        val marker = result[0].jsonObject
        assertEquals(JsonPrimitive(0), marker["index"], "the failed item's marker records its index")
        // The marker carries e.toString() (the class name) since e.message was null.
        assertTrue(
            marker["error"]?.toString()?.contains("NullMessageException") == true,
            "a null-message exception must fall through to toString(): ${marker["error"]}",
        )
    }

    // ----- runItem(): the executor.execute suspend point (line 139). When the executor actually
    // suspends (yields) before returning, the runItem state machine takes its resume arm. -----

    @Test
    fun `a body executor that suspends before completing still maps each item`() = runTest {
        val bodyId = Uuid.random()
        provides<PipelineService> { FakePipelineService(mapOf(bodyId to extractNBody(bodyId))) }
        provides<PipelineExecutor> { SuspendingExecutor(PipelineExecutorImpl()) }
        val node = ForEach(id = "each", pipelineId = bodyId, maxConcurrency = 2)
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1, 2)))))
        assertEquals(listOf(JsonPrimitive(1), JsonPrimitive(2)), (result.value as JsonArray).toList())
    }

    // ----- runItem(): the `requireCompleted()?.encode(...) ?: JsonNull` elvis (line 140). A body whose
    // Output node carries no value makes requireCompleted() return null, so each item's result falls
    // through to JsonNull — the elvis-right arm the value-producing body never reaches. -----

    /** A body with only an InputNode (no edge to an Output) — it completes but produces no Output value. */
    private fun nullOutputBody(id: UUID) = Pipeline(
        id = id, name = "body", acceptedInputType = "JSON",
        nodes = listOf(InputNode(id = "in", acceptedType = "JSON")),
        edges = emptyList(),
    )

    @Test
    fun `a body that produces no output yields a json null for each item`() = runTest {
        val bodyId = Uuid.random()
        register(nullOutputBody(bodyId))
        val node = ForEach(id = "each", pipelineId = bodyId)
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(1, 2)))))
        // Each item ran the body, which has no Output value -> requireCompleted() is null -> JsonNull.
        assertEquals(JsonArray(listOf(JsonNull, JsonNull)), result.value)
    }

    @Test
    fun `continueOnError with a no-output body still records a json null for each item`() = runTest {
        // The null-output elvis arm is taken on the happy (non-error) path even under continueOnError:
        // the items succeed (no exception) but carry no Output value.
        val bodyId = Uuid.random()
        register(nullOutputBody(bodyId))
        val node = ForEach(id = "each", pipelineId = bodyId, continueOnError = true)
        val result = node.executeForTestValue(context, NodeInputs(mapOf("in" to PipelineValue.ofJson(items(7))))).value as JsonArray
        assertEquals(1, result.size)
        assertEquals(JsonNull, result[0], "a no-output item is JsonNull, not an error marker")
    }

    /** A node whose failure carries a `null` message — drives the `e.message ?: e.toString()` elvis. */
    private class NullMessageException : RuntimeException(null as String?)

    private class NullMessageFailNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? =
            throw NullMessageException()
    }

    /** Delegates to a real executor but yields first, so the caller's suspend point genuinely suspends. */
    private class SuspendingExecutor(private val delegate: PipelineExecutor) : PipelineExecutor {
        override suspend fun execute(
            pipeline: ModelPipeline,
            input: PipelineValue?,
            context: PipelineContext,
            state: ExecutionState?,
        ): ExecutionResult {
            yield()
            return delegate.execute(pipeline, input, context, state)
        }
    }
}
