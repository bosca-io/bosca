package bosca.pipelines.builtin
import bosca.pipelines.testutil.executeForTest

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.PipelineGraph
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
import bosca.pipelines.service.requireCompleted
import bosca.pipelines.service.PipelineService
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
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

class RunPipelineNodeTest {

    @Serializable
    private data class Person(val name: String, val email: String)

    private val context get() = PipelineContext(AuthenticationContext(null, null), Json)

    /** Side-effect probe: counts how many times the executor actually ran it. */
    private class CountingNode(
        override val id: String,
        private val counter: AtomicInteger,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
            counter.incrementAndGet()
            return inputs.first
        }
    }

    /** Always fails — for asserting how a failing child run's trace merges. */
    private class FailingNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? =
            error("child failed")
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
            id: UUID,
            name: String,
            description: String,
            acceptedInputType: String,
            triggered: Boolean,
            version: Long,
            graph: JsonElement,
            tags: List<String>,
            key: String,
            api: Boolean,
            public: Boolean,
            schedule: String?,
            maxConcurrentRuns: Int?,
            maxRunsPerMinute: Int?,
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
        val service = FakePipelineService(pipelines.associateBy { it.id })
        provides<PipelineService> { service }
        provides<PipelineExecutor> { PipelineExecutorImpl() }
    }

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    private fun pipeline(id: UUID, nodes: List<PipelineNode>, edges: List<PipelineEdge>) =
        Pipeline(id = id, name = "p-$id", acceptedInputType = "Person", nodes = nodes, edges = edges)

    private val input = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())

    @Test
    fun `runs the referenced pipeline with the inbound value and outputs its result`() = runTest {
        val subId = Uuid.random()
        val sub = pipeline(
            id = subId,
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                JsonataNode(id = "jx", expression = "email"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "jx"),
                PipelineEdge(id = "e2", source = "jx", target = "out"),
            ),
        )
        val parent = pipeline(
            id = Uuid.random(),
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                RunPipelineNode(id = "sub", pipelineId = subId),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "sub"),
                PipelineEdge(id = "e2", source = "sub", target = "out"),
            ),
        )
        register(parent, sub)
        val result = PipelineExecutorImpl().execute(parent, input, context).requireCompleted()
        assertEquals(JsonPrimitive("ada@x.io"), result?.value)
    }

    @Test
    fun `mutually recursive pipelines abort with a cycle error before re-executing the root's nodes`() = runTest {
        val aId = Uuid.random()
        val bId = Uuid.random()
        val counter = AtomicInteger()
        val a = pipeline(
            id = aId,
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                CountingNode(id = "count", counter = counter),
                RunPipelineNode(id = "sub", pipelineId = bId),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "count"),
                PipelineEdge(id = "e2", source = "count", target = "sub"),
            ),
        )
        val b = pipeline(
            id = bId,
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), RunPipelineNode(id = "sub", pipelineId = aId)),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "sub")),
        )
        register(a, b)
        val failure = assertFailsWith<IllegalStateException> {
            PipelineExecutorImpl().execute(a, input, context)
        }
        assertTrue("re-enter" in (failure.message ?: ""), "expected a cycle error, got: ${failure.message}")
        assertEquals(1, counter.get(), "the root's nodes must not re-execute before the cycle check trips")
    }

    @Test
    fun `a self-invoking pipeline aborts with a cycle error before re-executing its nodes`() = runTest {
        val aId = Uuid.random()
        val counter = AtomicInteger()
        val a = pipeline(
            id = aId,
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                CountingNode(id = "count", counter = counter),
                RunPipelineNode(id = "sub", pipelineId = aId),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "count"),
                PipelineEdge(id = "e2", source = "count", target = "sub"),
            ),
        )
        register(a)
        val failure = assertFailsWith<IllegalStateException> {
            PipelineExecutorImpl().execute(a, input, context)
        }
        assertTrue("re-enter" in (failure.message ?: ""), "expected a cycle error, got: ${failure.message}")
        assertEquals(1, counter.get(), "the pipeline's nodes must not re-execute before the cycle check trips")
    }

    @Test
    fun `nesting beyond the maximum depth aborts`() = runTest {
        val subId = Uuid.random()
        val sub = pipeline(
            id = subId,
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "out")),
        )
        register(sub)
        val deepContext = PipelineContext(
            AuthenticationContext(null, null),
            Json,
            invocationStack = List(PipelineExecutorImpl.MAX_DEPTH) { Uuid.random() },
        )
        val node = RunPipelineNode(id = "sub", pipelineId = subId)
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(deepContext, NodeInputs(mapOf("in" to input)))
        }
        assertTrue("nesting depth" in (failure.message ?: ""), "expected a depth error, got: ${failure.message}")
    }

    @Test
    fun `a child run's trace entries merge under prefixed keys and never clobber the parent's`() = runTest {
        val subId = Uuid.random()
        val sub = pipeline(
            id = subId,
            // Child node ids deliberately collide with the parent's ("in", "out").
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                JsonataNode(id = "jx", expression = "email"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "jx"),
                PipelineEdge(id = "e2", source = "jx", target = "out"),
            ),
        )
        val parent = pipeline(
            id = Uuid.random(),
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                RunPipelineNode(id = "sub", pipelineId = subId),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "sub"),
                PipelineEdge(id = "e2", source = "sub", target = "out"),
            ),
        )
        register(parent, sub)
        val trace = DryRunTrace()
        val tracingContext = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        PipelineExecutorImpl().execute(parent, input, tracingContext)

        // Parent entries survive under their bare ids…
        assertEquals(input.encode(Json), trace.outputs["in"], "the parent input entry must survive the child run")
        assertEquals(JsonPrimitive("ada@x.io"), trace.outputs["out"], "the parent output entry must survive")
        assertTrue("sub" in trace.actions, "the Run Pipeline action itself is traced under its own id")
        // …child entries appear under "<run-pipeline-node-id>/<child-node-id>" keys…
        assertEquals(input.encode(Json), trace.outputs["sub/in"], "the child's colliding input entry is prefixed")
        assertEquals(JsonPrimitive("ada@x.io"), trace.outputs["sub/jx"])
        assertEquals(JsonPrimitive("ada@x.io"), trace.outputs["sub/out"], "the child's colliding output entry is prefixed")
        // …and never under bare child ids.
        assertFalse("jx" in trace.outputs, "child entries must not leak under bare node ids")
    }

    @Test
    fun `a failing child run's trace entries merge under prefixed keys`() = runTest {
        val subId = Uuid.random()
        val sub = pipeline(
            id = subId,
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                FailingNode(id = "jx"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "jx"),
                PipelineEdge(id = "e2", source = "jx", target = "out"),
            ),
        )
        register(sub)
        val trace = DryRunTrace()
        val tracingContext = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val node = RunPipelineNode(id = "sub", pipelineId = subId)
        assertFailsWith<Exception> { node.executeForTest(tracingContext, NodeInputs(mapOf("in" to input))) }
        assertTrue("sub/jx" in trace.errors, "the failing child node's error merges under the prefixed key")
        assertEquals(input.encode(Json), trace.outputs["sub/in"], "child outputs recorded before the failure merge too")
    }

    @Test
    fun `a graph with a target-less Run Pipeline node decodes, but executing it fails clearly`() = runTest {
        val json = Json {
            ignoreUnknownKeys = true
            serializersModule = SerializersModule {
                polymorphic(PipelineNode::class) {
                    subclass(InputNode::class, InputNode.serializer())
                    subclass(RunPipelineNode::class, RunPipelineNode.serializer())
                }
            }
        }
        // Studio seeds a freshly added node with no target — the save-time graph decode must accept it.
        val graph = json.decodeFromString(
            PipelineGraph.serializer(),
            """{"nodes":[{"type":"input","id":"in","acceptedType":"Person"},{"type":"runPipeline","id":"sub"}],"edges":[{"id":"e1","source":"in","target":"sub"}]}""",
        )
        val node = graph.nodes.filterIsInstance<RunPipelineNode>().single()
        assertNull(node.pipelineId, "an absent pipelineId must decode as null")

        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(mapOf("in" to input)))
        }
        assertTrue(
            "no target pipeline selected" in (failure.message ?: ""),
            "expected the no-target error, got: ${failure.message}",
        )
    }

    @Test
    fun `a declared input schema admits a conforming value`() = runTest {
        val subId = Uuid.random()
        val schema = Json.parseToJsonElement(
            """{"type": "object", "required": ["email"], "properties": {"email": {"type": "string"}}}""",
        )
        val sub = Pipeline(
            id = subId,
            name = "schema-sub",
            acceptedInputType = InputNode.JSON_TYPE,
            nodes = listOf(InputNode(id = "in", acceptedType = InputNode.JSON_TYPE, schema = schema), OutputNode(id = "out")),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "out")),
        )
        register(sub)
        val node = RunPipelineNode(id = "sub", pipelineId = subId)
        val result = node.executeForTest(context, bosca.pipelines.node.NodeInputs(mapOf("in" to input)))
        assertEquals(input.value, result?.value)
    }

    @Test
    fun `a declared input schema rejects a non-conforming value with the violations`() = runTest {
        val subId = Uuid.random()
        val schema = Json.parseToJsonElement(
            """{"type": "object", "required": ["subject"], "properties": {"subject": {"type": "string"}}}""",
        )
        val sub = Pipeline(
            id = subId,
            name = "schema-sub",
            acceptedInputType = InputNode.JSON_TYPE,
            nodes = listOf(InputNode(id = "in", acceptedType = InputNode.JSON_TYPE, schema = schema), OutputNode(id = "out")),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "out")),
        )
        register(sub)
        val node = RunPipelineNode(id = "sub", pipelineId = subId)
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, bosca.pipelines.node.NodeInputs(mapOf("in" to input)))
        }
        assertTrue("input schema" in (failure.message ?: ""), "expected a schema error, got: ${failure.message}")
        assertTrue("subject" in (failure.message ?: ""), "the violation should name the field: ${failure.message}")
    }

    @Test
    fun `a missing pipeline fails the run with a clear error`() = runTest {
        val missing = Uuid.random()
        val parent = pipeline(
            id = Uuid.random(),
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), RunPipelineNode(id = "sub", pipelineId = missing)),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "sub")),
        )
        register(parent)
        val failure = assertFailsWith<IllegalStateException> {
            PipelineExecutorImpl().execute(parent, input, context)
        }
        assertTrue("pipeline not found" in (failure.message ?: ""), "expected not-found, got: ${failure.message}")
    }

    // ---- run() durable path (runId != null, not dry) — parks and hands off to the run service ----

    @Test
    fun `a durable run parks the node and hands the child off to the run service`() = runTest {
        val pid = Uuid.random()
        val runId = Uuid.random()
        val runService = mockk<PipelineRunService>(relaxed = true)
        provides<PipelineRunService> { runService }
        val createdAt = OffsetDateTime.now()
        val durableContext = PipelineContext(
            AuthenticationContext(null, null),
            Json,
            inputCreated = createdAt,
            runId = runId,
        )
        val node = RunPipelineNode(id = "sub", pipelineId = pid)

        val result = node.run(durableContext, NodeInputs(mapOf("in" to input)))

        val parked = assertIs<NodeResult.Suspend>(result)
        // The hand-off is deferred to the executor — nothing runs until the await is durably persisted.
        coVerify(exactly = 0) { runService.runChildPipeline(any(), any(), any(), any(), any()) }
        // Driving the enqueue thunk dispatches the child run with the threaded run id / clock.
        parked.enqueue()
        coVerify(exactly = 1) {
            runService.runChildPipeline(
                match { it == runId },
                match { it == "sub" },
                match { it == pid },
                match { it.value == input.value },
                match { it == createdAt },
            )
        }
    }

    @Test
    fun `a durable run with no inbound value fails with the requires-input error naming the node`() = runTest {
        val durableContext = PipelineContext(
            AuthenticationContext(null, null),
            Json,
            runId = Uuid.random(),
        )
        val node = RunPipelineNode(id = "sub", name = "MyRunner", pipelineId = Uuid.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.run(durableContext, NodeInputs(emptyMap()))
        }
        assertTrue("requires an input" in (failure.message ?: ""), "expected requires-input, got: ${failure.message}")
        // name is set, so the message identifies the node by its name, not its id.
        assertTrue("MyRunner" in (failure.message ?: ""), "expected the node name, got: ${failure.message}")
    }

    @Test
    fun `a durable run with no target pipeline fails with the no-target error using the id when name is blank`() = runTest {
        val durableContext = PipelineContext(
            AuthenticationContext(null, null),
            Json,
            runId = Uuid.random(),
        )
        val node = RunPipelineNode(id = "sub-id", pipelineId = null)
        val failure = assertFailsWith<IllegalStateException> {
            node.run(durableContext, NodeInputs(mapOf("in" to input)))
        }
        assertTrue("no target pipeline selected" in (failure.message ?: ""), "expected no-target, got: ${failure.message}")
        // name is blank, so the message falls back to the id.
        assertTrue("sub-id" in (failure.message ?: ""), "expected the node id, got: ${failure.message}")
    }

    // ---- run() falls back to the synchronous execute() path (dry-run, or non-durable) ----

    @Test
    fun `a dry run descends synchronously via execute instead of parking a durable child`() = runTest {
        val subId = Uuid.random()
        val sub = pipeline(
            id = subId,
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                JsonataNode(id = "jx", expression = "email"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "jx"),
                PipelineEdge(id = "e2", source = "jx", target = "out"),
            ),
        )
        register(sub)
        // dryRun = true AND a real runId: the dry-run arm of the `||` wins, so this stays synchronous.
        val dryContext = PipelineContext(
            AuthenticationContext(null, null),
            Json,
            dryRun = true,
            runId = Uuid.random(),
        )
        val node = RunPipelineNode(id = "sub", pipelineId = subId)
        val result = node.run(dryContext, NodeInputs(mapOf("in" to input)))
        val output = assertIs<NodeResult.Output>(result)
        assertEquals(JsonPrimitive("ada@x.io"), output.value?.value)
    }

    @Test
    fun `a non-durable run with a null runId descends synchronously via execute`() = runTest {
        val subId = Uuid.random()
        val sub = pipeline(
            id = subId,
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                JsonataNode(id = "jx", expression = "email"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "jx"),
                PipelineEdge(id = "e2", source = "jx", target = "out"),
            ),
        )
        register(sub)
        // dryRun = false but runId = null: the right arm of the `||` wins, still synchronous.
        val node = RunPipelineNode(id = "sub", pipelineId = subId)
        val result = node.run(context, NodeInputs(mapOf("in" to input)))
        val output = assertIs<NodeResult.Output>(result)
        assertEquals(JsonPrimitive("ada@x.io"), output.value?.value)
    }

    // ---- execute() remaining branches ----

    @Test
    fun `execute with an empty inbound map fails with the requires-input error`() = runTest {
        val node = RunPipelineNode(id = "sub", pipelineId = Uuid.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue("requires an input" in (failure.message ?: ""), "expected requires-input, got: ${failure.message}")
        // blank name → identified by id.
        assertTrue("sub" in (failure.message ?: ""), "expected the id, got: ${failure.message}")
    }

    @Test
    fun `execute with a null pipelineId fails with the no-target error naming the node`() = runTest {
        val node = RunPipelineNode(id = "sub", name = "Composer", pipelineId = null)
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(mapOf("in" to input)))
        }
        assertTrue("no target pipeline selected" in (failure.message ?: ""), "expected no-target, got: ${failure.message}")
        // name is set → identified by name.
        assertTrue("Composer" in (failure.message ?: ""), "expected the node name, got: ${failure.message}")
    }

    @Test
    fun `a child pipeline without an InputNode skips schema validation and surfaces the executor error`() = runTest {
        val subId = Uuid.random()
        // No InputNode at all → RunPipelineNode.execute's `inputNode?.schema?.let` is skipped (the
        // inputNode-null arm), the action is still traced, then the executor itself rejects the graph.
        val sub = pipeline(
            id = subId,
            nodes = listOf(OutputNode(id = "out")),
            edges = emptyList(),
        )
        register(sub)
        val node = RunPipelineNode(id = "sub", pipelineId = subId)
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(mapOf("in" to input)))
        }
        // The failure is the executor's no-InputNode error, NOT a schema-validation error — proving the
        // schema arm was bypassed because inputNode was null.
        assertTrue("no InputNode" in (failure.message ?: ""), "expected the executor error, got: ${failure.message}")
        assertFalse("input schema" in (failure.message ?: ""), "schema validation must not have run")
    }

    @Test
    fun `execute records the run-pipeline action into a present trace before descending`() = runTest {
        val subId = Uuid.random()
        val sub = pipeline(
            id = subId,
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "out")),
        )
        register(sub)
        val trace = DryRunTrace()
        val tracingContext = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val node = RunPipelineNode(id = "sub", pipelineId = subId)
        node.executeForTest(tracingContext, NodeInputs(mapOf("in" to input)))

        // The action entry is recorded under this node's own id (the trace != null arm of `?.set`).
        assertTrue("sub" in trace.actions, "the run-pipeline action must be traced under the node id")
        val action = Json.encodeToString(JsonElement.serializer(), trace.actions.getValue("sub"))
        assertTrue("runPipeline" in action, "the traced action names the action: $action")
        assertTrue(subId.toString() in action, "the traced action carries the target pipeline id: $action")
        assertTrue(sub.name in action, "the traced action carries the target pipeline name: $action")
    }

    @Test
    fun `execute without a trace returns the child output and records nothing`() = runTest {
        // The trace-null arms of `?.actions?.set`, the `childTrace` let, and the merge `if` are all
        // skipped here; the node simply returns the child's Output value.
        val subId = Uuid.random()
        val sub = pipeline(
            id = subId,
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                JsonataNode(id = "jx", expression = "name"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "jx"),
                PipelineEdge(id = "e2", source = "jx", target = "out"),
            ),
        )
        register(sub)
        val node = RunPipelineNode(id = "sub", pipelineId = subId)
        val result = node.executeForTest(context, NodeInputs(mapOf("in" to input)))
        assertEquals(JsonPrimitive("Ada"), result?.value)
    }

    @Test
    fun `a child output node with no value yields a null node output`() = runTest {
        // The child completes but its OutputNode carries no value → requireCompleted returns null.
        val subId = Uuid.random()
        val sub = pipeline(
            id = subId,
            nodes = listOf(InputNode(id = "in", acceptedType = "Person")),
            edges = emptyList(),
        )
        register(sub)
        val node = RunPipelineNode(id = "sub", pipelineId = subId)
        val result = node.executeForTest(context, NodeInputs(mapOf("in" to input)))
        assertNull(result, "a child with no Output value produces no node output")
    }

    @Test
    fun `the synchronous and durable input-required errors carry the same node identity`() = runTest {
        // run() and execute() share the same `name.ifBlank { id }` identity rendering for the input
        // guard — assert they agree so neither drifts.
        val node = RunPipelineNode(id = "the-id", pipelineId = Uuid.random())
        val durableContext = PipelineContext(AuthenticationContext(null, null), Json, runId = Uuid.random())
        val runFailure = assertFailsWith<IllegalStateException> { node.run(durableContext, NodeInputs(emptyMap())) }
        val execFailure = assertFailsWith<IllegalStateException> { node.executeForTest(context, NodeInputs(emptyMap())) }
        assertEquals(runFailure.message, execFailure.message)
        assertTrue("the-id" in (runFailure.message ?: ""))
    }

    @Test
    fun `the child run executes under a sibling context that shares the parent invocation stack`() = runTest {
        // Guards against regressions in how execute() builds the child PipelineContext: a non-empty
        // invocation stack must thread through so the cross-pipeline cycle/depth guards keep working.
        val subId = Uuid.random()
        val sub = pipeline(
            id = subId,
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "out")),
        )
        register(sub)
        val priorId = Uuid.random()
        val stackedContext = PipelineContext(
            AuthenticationContext(null, null),
            Json,
            invocationStack = listOf(priorId),
        )
        val node = RunPipelineNode(id = "sub", pipelineId = subId)
        // Executing succeeds: the inherited single-element stack is well under MAX_DEPTH and does not
        // contain the child, so neither guard trips.
        val result = node.executeForTest(stackedContext, NodeInputs(mapOf("in" to input)))
        assertEquals(input.value, result?.value)
        // The parent context's own stack is not mutated by the descent.
        assertEquals(listOf(priorId), stackedContext.invocationStack)
    }

    @Test
    fun `non-durable run and execute both surface the no-target error identically`() = runTest {
        val node = RunPipelineNode(id = "n", name = "Same", pipelineId = null)
        val durableContext = PipelineContext(AuthenticationContext(null, null), Json, runId = Uuid.random())
        val runFailure = assertFailsWith<IllegalStateException> {
            node.run(durableContext, NodeInputs(mapOf("in" to input)))
        }
        val execFailure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(mapOf("in" to input)))
        }
        assertEquals(runFailure.message, execFailure.message)
    }

    // ----- synthetic default-args constructor: distinct PARTIAL subsets of the optional args -----
    // Each construction leaves a different combination of optional parameters at default, exercising
    // the <init>$default mask branches and reading the resulting defaulted getters.

    @Test
    fun `constructs with only id and reads every defaulted field`() = runTest {
        val node = RunPipelineNode(id = "sub")
        assertEquals("sub", node.id)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertNull(node.pipelineId)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `constructs with id plus name leaving the rest default`() = runTest {
        val node = RunPipelineNode(id = "sub", name = "Composer")
        assertEquals("Composer", node.name)
        assertEquals("", node.description)
        assertNull(node.pipelineId)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `constructs with id plus description leaving the rest default`() = runTest {
        val node = RunPipelineNode(id = "sub", description = "runs a sub-pipeline")
        assertEquals("runs a sub-pipeline", node.description)
        assertEquals("", node.name)
        assertNull(node.pipelineId)
    }

    @Test
    fun `constructs with id plus pipelineId leaving the rest default`() = runTest {
        val pid = Uuid.random()
        val node = RunPipelineNode(id = "sub", pipelineId = pid)
        assertEquals(pid, node.pipelineId)
        assertEquals("", node.name)
        assertEquals("", node.description)
        assertEquals(NodePosition(), node.position)
    }

    @Test
    fun `constructs with an explicit position leaving the rest default`() = runTest {
        val node = RunPipelineNode(id = "sub", position = NodePosition(x = 3.0, y = 4.0))
        assertEquals(NodePosition(x = 3.0, y = 4.0), node.position)
        assertEquals(3.0, node.position.x)
        assertEquals(4.0, node.position.y)
        assertEquals("", node.name)
        assertNull(node.pipelineId)
    }

    // ----- @Serializable: the missing-required-field throw arm of the generated deserialization
    // constructor (line 45 header). Decoding JSON without the required `id` must take the
    // throwMissingFieldException arm the synthetic constructor's mask check guards. -----

    @Test
    fun `decoding JSON missing the required id throws a serialization exception`() = runTest {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(RunPipelineNode.serializer(), """{"name":"x"}""")
        }
    }

    // ----- execute(): the OTHER `name.ifBlank { id }` arm of each guard error. The existing tests cover
    // only one side of the isBlank branch per message; these add the complement so both arms are taken. -----

    @Test
    fun `execute with an empty inbound map and a name set names the node`() = runTest {
        // execute() requires-input error with a name SET -> the ifBlank-false (use name) arm of line 82.
        val node = RunPipelineNode(id = "sub", name = "Composer", pipelineId = Uuid.random())
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(emptyMap()))
        }
        assertTrue("requires an input" in (failure.message ?: ""), "expected requires-input, got: ${failure.message}")
        assertTrue("Composer" in (failure.message ?: ""), "the name should appear when set: ${failure.message}")
    }

    @Test
    fun `execute when the pipeline is not found and a name set names the node`() = runTest {
        // execute() not-found error with a name SET -> the ifBlank-false (use name) arm of line 86.
        val missing = Uuid.random()
        register() // no pipelines -> get(missing) returns null
        val node = RunPipelineNode(id = "sub", name = "Composer", pipelineId = missing)
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(mapOf("in" to input)))
        }
        assertTrue("pipeline not found" in (failure.message ?: ""), "expected not-found, got: ${failure.message}")
        assertTrue("Composer" in (failure.message ?: ""), "the name should appear when set: ${failure.message}")
    }

    @Test
    fun `a declared input schema rejection with a name set names the node`() = runTest {
        // execute() schema-violation check with a name SET -> the ifBlank-false (use name) arm of line 90.
        val subId = Uuid.random()
        val schema = Json.parseToJsonElement(
            """{"type": "object", "required": ["subject"], "properties": {"subject": {"type": "string"}}}""",
        )
        val sub = Pipeline(
            id = subId,
            name = "schema-sub",
            acceptedInputType = InputNode.JSON_TYPE,
            nodes = listOf(InputNode(id = "in", acceptedType = InputNode.JSON_TYPE, schema = schema), OutputNode(id = "out")),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "out")),
        )
        register(sub)
        val node = RunPipelineNode(id = "sub", name = "Composer", pipelineId = subId)
        val failure = assertFailsWith<IllegalStateException> {
            node.executeForTest(context, NodeInputs(mapOf("in" to input)))
        }
        assertTrue("input schema" in (failure.message ?: ""), "expected a schema error, got: ${failure.message}")
        assertTrue("Composer" in (failure.message ?: ""), "the name should appear when set: ${failure.message}")
    }
}
