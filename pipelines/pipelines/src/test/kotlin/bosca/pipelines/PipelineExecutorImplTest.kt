package bosca.pipelines

import bosca.pipelines.builtin.ConditionNode
import bosca.pipelines.builtin.JsonataNode
import bosca.pipelines.builtin.ObjectsToMapNode
import bosca.pipelines.builtin.SwitchCase
import bosca.pipelines.builtin.SwitchNode
import bosca.pipelines.model.NodeExecutionStatus
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.RetryPolicy
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodeExecutionEvent
import bosca.pipelines.node.NodeExecutionSink
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.requireCompleted
import bosca.pipelines.service.ExecutionState
import bosca.security.service.AuthenticationContext
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.NodeInputSlot
import bosca.pipelines.node.NodeOutputSlot
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.TransformNode
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PipelineExecutorImplTest {

    @Serializable
    private data class Person(val name: String, val email: String)

    private val context = PipelineContext(AuthenticationContext(null, null), Json)
    private val executor = PipelineExecutorImpl()

    private class YieldingTransform(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
            yield()
            return inputs.first
        }
    }

    private fun pipeline(nodes: List<bosca.pipelines.node.PipelineNode>, edges: List<PipelineEdge>) =
        Pipeline(id = Uuid.random(), name = "test", acceptedInputType = "Person", nodes = nodes, edges = edges)

    @Test
    fun `executor preserves values when node work genuinely suspends with and without a timeout`() = runTest {
        val direct = YieldingTransform("direct")
        val timed = YieldingTransform("timed").apply { timeoutSeconds = 5 }
        val p = pipeline(
            nodes = listOf(InputNode("in", acceptedType = "Person"), direct, timed, OutputNode("out")),
            edges = listOf(
                PipelineEdge("e1", "in", "direct"),
                PipelineEdge("e2", "direct", "timed"),
                PipelineEdge("e3", "timed", "out"),
            ),
        )

        val input = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        val output = executor.execute(p, input, context).requireCompleted()

        assertEquals(input.value, output?.value)
    }

    @Test
    fun `JSONata extracts a field from a typed input bridged to JSON`() = runTest {
        val p = pipeline(
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
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), context).requireCompleted()
        assertEquals(JsonPrimitive("ada@x.io"), result?.value)
    }

    @Test
    fun `ObjectsToMap keys a branch by target port, then JSONata reads across it`() = runTest {
        // in (Person) --[port=p]--> map {"p": …} --> jx (p.email) --> out
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                ObjectsToMapNode(id = "map"),
                JsonataNode(id = "jx", expression = "p.email"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "map", targetPort = "p"),
                PipelineEdge(id = "e2", source = "map", target = "jx"),
                PipelineEdge(id = "e3", source = "jx", target = "out"),
            ),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), context).requireCompleted()
        assertEquals(JsonPrimitive("ada@x.io"), result?.value)
    }

    @Test
    fun `condition routes the taken branch and skips the other subtree`() = runTest {
        // in --> cond("email" truthy) --true--> jxTrue --> out
        //                             --false-> jxFalse        (must be skipped)
        val trace = DryRunTrace()
        val tracingContext = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                ConditionNode(id = "cond", expression = "email"),
                JsonataNode(id = "jxTrue", expression = "'has-email'"),
                JsonataNode(id = "jxFalse", expression = "'no-email'"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "cond"),
                PipelineEdge(id = "e2", source = "cond", target = "jxTrue", sourcePort = "true"),
                PipelineEdge(id = "e3", source = "cond", target = "jxFalse", sourcePort = "false"),
                PipelineEdge(id = "e4", source = "jxTrue", target = "out"),
            ),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), tracingContext).requireCompleted()
        assertEquals(JsonPrimitive("has-email"), result?.value)
        assertTrue("jxTrue" in trace.outputs, "taken branch should execute")
        assertTrue("jxFalse" !in trace.outputs, "untaken branch must be skipped")
    }

    @Test
    fun `JSONata and Condition expressions can read the run's eventCreated binding`() = runTest {
        val firedAt = bosca.serialization.OffsetDateTime.parse("2026-06-09T12:00:00Z")
        val timedContext = PipelineContext(AuthenticationContext(null, null), Json, inputCreated = firedAt)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                ConditionNode(id = "cond", expression = "\$eventCreated != null"),
                JsonataNode(id = "jx", expression = "\$eventCreated"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "cond"),
                PipelineEdge(id = "e2", source = "cond", target = "jx", sourcePort = "true"),
                PipelineEdge(id = "e3", source = "jx", target = "out"),
            ),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), timedContext).requireCompleted()
        assertEquals(JsonPrimitive(firedAt.toString()), result?.value)
    }

    @Test
    fun `a pipeline with no OutputNode returns null`() = runTest {
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), JsonataNode(id = "jx", expression = "email")),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "jx")),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), context).requireCompleted()
        assertEquals(null, result)
    }

    @Test
    fun `switch routes the matched case and skips the other branches`() = runTest {
        // in --> switch(name='Ada' -> ada) --[ada]--> jxAda --> out ;  --[default]--> jxOther (skipped)
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                SwitchNode(id = "sw", cases = listOf(SwitchCase("ada", "name = 'Ada'"), SwitchCase("bob", "name = 'Bob'"))),
                JsonataNode(id = "jxAda", expression = "'is-ada'"),
                JsonataNode(id = "jxOther", expression = "'other'"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "sw"),
                PipelineEdge(id = "e2", source = "sw", target = "jxAda", sourcePort = "ada"),
                PipelineEdge(id = "e3", source = "sw", target = "jxOther", sourcePort = "default"),
                PipelineEdge(id = "e4", source = "jxAda", target = "out"),
            ),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertEquals(JsonPrimitive("is-ada"), result?.value)
        assertTrue("jxAda" in trace.outputs, "the matched case's branch runs")
        assertTrue("jxOther" !in trace.outputs, "the default (and the unmatched 'bob') branch is skipped")
    }

    @Test
    fun `the node-execution sink gets an OK event per run node and SKIPPED for the untaken branch`() = runTest {
        // in --> cond("email") --true--> jxTrue --> out ;  --false--> jxFalse (skipped)
        val sink = CapturingSink()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, nodeSink = sink, runId = Uuid.random())
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                ConditionNode(id = "cond", expression = "email"),
                JsonataNode(id = "jxTrue", expression = "'has-email'"),
                JsonataNode(id = "jxFalse", expression = "'no-email'"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "cond"),
                PipelineEdge(id = "e2", source = "cond", target = "jxTrue", sourcePort = "true"),
                PipelineEdge(id = "e3", source = "cond", target = "jxFalse", sourcePort = "false"),
                PipelineEdge(id = "e4", source = "jxTrue", target = "out"),
            ),
        )
        executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()

        val byNode = sink.events.associate { it.nodeId to it.status }
        // The seed InputNode is not "run" — no event; every other node has exactly one.
        assertEquals(setOf("cond", "jxTrue", "jxFalse", "out"), byNode.keys)
        assertEquals(NodeExecutionStatus.OK, byNode["cond"])
        assertEquals(NodeExecutionStatus.OK, byNode["jxTrue"])
        assertEquals(NodeExecutionStatus.SKIPPED, byNode["jxFalse"])
        assertEquals(NodeExecutionStatus.OK, byNode["out"])
        // The condition's OK event carries the routed port it emitted on.
        assertEquals("true", sink.events.first { it.nodeId == "cond" }.port)
    }

    @Test
    fun `a failing node emits a FAILED event before the error propagates`() = runTest {
        val sink = CapturingSink()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, nodeSink = sink, runId = Uuid.random())
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), BoomNode(id = "boom"), OutputNode(id = "out")),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "boom"),
                PipelineEdge(id = "e2", source = "boom", target = "out"),
            ),
        )
        assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx)
        }
        val boom = sink.events.first { it.nodeId == "boom" }
        assertEquals(NodeExecutionStatus.FAILED, boom.status)
        assertTrue("boom" in (boom.error ?: ""), "the failure message should be captured")
    }

    /** A node that always throws — to exercise the executor's FAILED event path. */
    @Serializable
    private class BoomNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            error("boom")
    }

    private class CapturingSink : NodeExecutionSink {
        val events = java.util.concurrent.CopyOnWriteArrayList<NodeExecutionEvent>()
        override fun record(event: NodeExecutionEvent) {
            events.add(event)
        }
    }

    @Test
    fun `retry re-runs a flaky node until it succeeds`() = runTest {
        FlakyNode.attempts.set(0)
        FlakyNode.succeedOnAttempt = 2
        val sink = CapturingSink()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, nodeSink = sink, runId = Uuid.random())
        val flaky = FlakyNode(id = "flaky").apply { retry = RetryPolicy(maxAttempts = 3) }
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), flaky, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "flaky"), PipelineEdge("e2", "flaky", "out")),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertEquals(2, FlakyNode.attempts.get(), "it should have taken two attempts")
        assertTrue(result != null)
        // Timeline shows the failed first attempt then the successful run.
        val flakyEvents = sink.events.filter { it.nodeId == "flaky" }.map { it.status }
        assertEquals(listOf(NodeExecutionStatus.FAILED, NodeExecutionStatus.OK), flakyEvents)
    }

    @Test
    fun `retry is bounded by maxAttempts then the failure propagates`() = runTest {
        val sink = CapturingSink()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, nodeSink = sink, runId = Uuid.random())
        val boom = BoomNode(id = "boom").apply { retry = RetryPolicy(maxAttempts = 3) }
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), boom, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "boom"), PipelineEdge("e2", "boom", "out")),
        )
        assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx)
        }
        // Exactly maxAttempts failed-attempt events, no OK.
        val boomEvents = sink.events.filter { it.nodeId == "boom" }
        assertEquals(3, boomEvents.size)
        assertTrue(boomEvents.all { it.status == NodeExecutionStatus.FAILED })
    }

    @Test
    fun `a node exceeding its timeout fails the run`() = runTest {
        val sink = CapturingSink()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, nodeSink = sink, runId = Uuid.random())
        val slow = SlowNode(id = "slow").apply { timeoutSeconds = 1 }
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), slow, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "slow"), PipelineEdge("e2", "slow", "out")),
        )
        val failure = assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx)
        }
        assertTrue("timed out" in (failure.message ?: ""), "expected a timeout error, got: ${failure.message}")
    }

    /** A node that fails until [FlakyNode.succeedOnAttempt] — for exercising retry. */
    @Serializable
    private class FlakyNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? {
            val n = attempts.incrementAndGet()
            if (n < succeedOnAttempt) error("flaky attempt $n")
            return inputs.first
        }
        companion object {
            val attempts = java.util.concurrent.atomic.AtomicInteger(0)
            var succeedOnAttempt = 1
        }
    }

    /** A node that takes far longer than any sane timeout — for exercising timeout. */
    @Serializable
    private class SlowNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? {
            kotlinx.coroutines.delay(10_000)
            return inputs.first
        }
    }

    @Test
    fun `a cyclic graph fails fast instead of hanging`() = runTest {
        // in -> jx1 -> jx2 -> jx1 : jx1 and jx2 are mutually dependent, so neither is ever ready
        // and nothing is parked — the executor must detect the deadlock, not loop forever.
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                JsonataNode(id = "jx1", expression = "email"),
                JsonataNode(id = "jx2", expression = "email"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "jx1"),
                PipelineEdge(id = "e2", source = "jx2", target = "jx1"),
                PipelineEdge(id = "e3", source = "jx1", target = "jx2"),
            ),
        )
        assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), context)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Invocation-stack guards (cross-pipeline recursion + max nesting depth)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `re-entering a pipeline already on the invocation stack fails fast with the chain`() = runTest {
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "out")),
        )
        // The pipeline's own id is already on the stack — running it would re-enter it (a cycle).
        val recursiveCtx = PipelineContext(
            AuthenticationContext(null, null), Json,
            invocationStack = listOf(p.id),
        )
        val failure = assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), recursiveCtx)
        }
        assertTrue("re-enter pipeline" in (failure.message ?: ""), "message names the re-entry")
        assertTrue(p.id.toString() in (failure.message ?: ""), "the chain includes the offending id")
    }

    @Test
    fun `exceeding the maximum nesting depth fails fast`() = runTest {
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "out")),
        )
        // A stack already at MAX_DEPTH distinct ids (none equal to p.id, so the cycle check passes
        // and the depth check is the one that fires).
        val deepStack = (0 until PipelineExecutorImpl.MAX_DEPTH).map { Uuid.random() }
        val deepCtx = PipelineContext(
            AuthenticationContext(null, null), Json,
            invocationStack = deepStack,
        )
        val failure = assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), deepCtx)
        }
        assertTrue("maximum pipeline nesting depth" in (failure.message ?: ""), "message names the depth cap")
    }

    @Test
    fun `a stack just under the depth cap with a distinct id still runs`() = runTest {
        val p = pipeline(
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
        // MAX_DEPTH - 1 distinct ancestors → size < MAX_DEPTH and p.id not present: both checks pass.
        val nearMax = (0 until PipelineExecutorImpl.MAX_DEPTH - 1).map { Uuid.random() }
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, invocationStack = nearMax)
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertEquals(JsonPrimitive("ada@x.io"), result?.value)
    }

    // ----------------------------------------------------------------------------------------------
    // Fresh-run seeding errors
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a fresh run of a pipeline with no InputNode fails`() = runTest {
        val p = pipeline(
            nodes = listOf(JsonataNode(id = "jx", expression = "email"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge(id = "e1", source = "jx", target = "out")),
        )
        val failure = assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), context)
        }
        assertTrue("has no InputNode" in (failure.message ?: ""), "names the missing InputNode")
    }

    @Test
    fun `a fresh run with a null input value fails`() = runTest {
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "out")),
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            executor.execute(p, input = null, context = context)
        }
        assertTrue("requires an input value" in (failure.message ?: ""), "names the missing input")
    }

    // ----------------------------------------------------------------------------------------------
    // Suspend / park / resume
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a suspending node parks the run and surfaces a Parked with its enqueue`() = runTest {
        SuspendingTestNode.enqueued.set(false)
        val sink = CapturingSink()
        val susp = SuspendingTestNode(id = "susp")
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, nodeSink = sink, runId = Uuid.random())
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), susp, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "susp"), PipelineEdge("e2", "susp", "out")),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx)
        val suspended = result as bosca.pipelines.service.ExecutionResult.Suspended

        // The checkpoint records the seed input and marks the node as awaiting; the downstream out is unrun.
        assertEquals(setOf("susp"), suspended.state.awaiting)
        assertTrue("in" in suspended.state.outputs, "the seed InputNode output is checkpointed")
        assertTrue("out" !in suspended.state.outputs, "downstream of a parked node does not run")
        // Exactly one newly-parked node carrying its await key and deferred enqueue.
        assertEquals(1, suspended.newlyParked.size)
        val parked = suspended.newlyParked.single()
        assertEquals("susp", parked.nodeId)
        // The executor never fired the enqueue itself; the caller does, after persisting.
        assertEquals(false, SuspendingTestNode.enqueued.get())
        parked.enqueue()
        assertEquals(true, SuspendingTestNode.enqueued.get())
        // A SUSPENDED timeline event was emitted for the parked node, and nothing for the downstream.
        assertEquals(NodeExecutionStatus.SUSPENDED, sink.events.first { it.nodeId == "susp" }.status)
        assertTrue(sink.events.none { it.nodeId == "out" }, "no event for the unrun downstream")
    }

    @Test
    fun `resuming from a checkpoint runs only the downstream and completes`() = runTest {
        // Resume state: the input and the parked node have both produced outputs; nothing still awaits.
        val seedIn = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        val suspOut = PipelineValue.ofJson(JsonPrimitive("resumed"))
        val sink = CapturingSink()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, nodeSink = sink, runId = Uuid.random())
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), SuspendingTestNode(id = "susp"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "susp"), PipelineEdge("e2", "susp", "out")),
        )
        val state = bosca.pipelines.service.ExecutionState(
            outputs = mapOf("in" to seedIn, "susp" to suspOut),
            awaiting = emptySet(),
        )
        // input is ignored on resume (state != null), so pass null to prove that arm.
        val result = executor.execute(p, input = null, context = ctx, state = state).requireCompleted()
        assertEquals(JsonPrimitive("resumed"), result?.value)
        // Only `out` ran on the resume — the already-evaluated `in` and `susp` are never re-run.
        assertEquals(setOf("out"), sink.events.map { it.nodeId }.toSet())
    }

    @Test
    fun `resuming with a node still awaiting neither re-runs it nor completes`() = runTest {
        // Two parking branches; resume with one already done and the other still awaiting from a prior pass.
        SuspendingTestNode.enqueued.set(false)
        val seedIn = PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer())
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, runId = Uuid.random())
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                SuspendingTestNode(id = "A"),
                SuspendingTestNode(id = "B"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge("e1", "in", "A"),
                PipelineEdge("e2", "in", "B"),
                PipelineEdge("e3", "A", "out"),
                PipelineEdge("e4", "B", "out"),
            ),
        )
        // B is still parked (in `awaiting`); A has produced its output. Nothing new can run because
        // `out` needs both A and B, and B has no output yet.
        val state = bosca.pipelines.service.ExecutionState(
            outputs = mapOf("in" to seedIn, "A" to PipelineValue.ofJson(JsonPrimitive("a"))),
            awaiting = setOf("B"),
        )
        val result = executor.execute(p, input = null, context = ctx, state = state)
        val suspended = result as bosca.pipelines.service.ExecutionResult.Suspended
        // The run stays parked on B (carried forward), and nothing newly parked (no progress).
        assertEquals(setOf("B"), suspended.state.awaiting)
        assertTrue(suspended.newlyParked.isEmpty(), "a no-progress resume parks nothing new")
        // B (still awaiting) was never re-run, so its enqueue never fired again.
        assertEquals(false, SuspendingTestNode.enqueued.get())
    }

    @Test
    fun `a suspending node in a non-durable run falls back to synchronous output`() = runTest {
        // runId == null: SuspendingTestNode.run degrades to a plain Output (cannot park with nowhere to resume).
        val ctx = PipelineContext(AuthenticationContext(null, null), Json) // no runId
        val susp = SuspendingTestNode(id = "susp")
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), susp, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "susp"), PipelineEdge("e2", "susp", "out")),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        // It passed the input through synchronously instead of parking — the typed Person flows through.
        assertEquals(Person("Ada", "ada@x.io"), result?.value)
    }

    // ----------------------------------------------------------------------------------------------
    // Rollback-sink emission + the context rebuild that carries rollbackSink
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a node with a rollback pipeline emits a RollbackEvent carrying its encoded output`() = runTest {
        val rollbackId = Uuid.random()
        val rollbackSink = CapturingRollbackSink()
        val node = RollbackTestNode(id = "act").apply { rollbackPipeline = rollbackId }
        val ctx = PipelineContext(
            AuthenticationContext(null, null), Json,
            runId = Uuid.random(),
            rollbackSink = rollbackSink,
        )
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), node, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "act"), PipelineEdge("e2", "act", "out")),
        )
        executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()

        // The rollback sink received exactly one event for the rollback-enabled node, with the
        // rollback pipeline id and the node's encoded output (proving encoded was computed for the sink).
        assertEquals(1, rollbackSink.events.size)
        val ev = rollbackSink.events.single()
        assertEquals("act", ev.nodeId)
        assertEquals(rollbackId, ev.rollbackPipelineId)
        assertEquals("ada@x.io", (ev.output as? kotlinx.serialization.json.JsonObject)?.get("email")?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `a rollback-enabled node that produces no output emits a RollbackEvent with null output`() = runTest {
        val rollbackId = Uuid.random()
        val rollbackSink = CapturingRollbackSink()
        val node = NullOutputRollbackNode(id = "act").apply { rollbackPipeline = rollbackId }
        val ctx = PipelineContext(
            AuthenticationContext(null, null), Json,
            runId = Uuid.random(),
            rollbackSink = rollbackSink,
        )
        // No OutputNode wired downstream of the null-output node — the run completes regardless.
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), node),
            edges = listOf(PipelineEdge("e1", "in", "act")),
        )
        executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()

        assertEquals(1, rollbackSink.events.size)
        // out == null → encoded is null → the event carries a null output.
        assertEquals(null, rollbackSink.events.single().output)
    }

    @Test
    fun `a node without a rollback pipeline emits nothing to the rollback sink`() = runTest {
        val rollbackSink = CapturingRollbackSink()
        val ctx = PipelineContext(
            AuthenticationContext(null, null), Json,
            runId = Uuid.random(),
            rollbackSink = rollbackSink,
        )
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                JsonataNode(id = "jx", expression = "email"),
                OutputNode(id = "out"),
            ),
            edges = listOf(PipelineEdge("e1", "in", "jx"), PipelineEdge("e2", "jx", "out")),
        )
        executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertTrue(rollbackSink.events.isEmpty(), "no rollback pipeline declared → no rollback events")
    }

    // ----------------------------------------------------------------------------------------------
    // Ported-edge propagation skip in gatherInputs
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a ported value does not flow down an edge whose sourcePort names a different branch`() = runTest {
        // route emits on port "a"; edge to keep matches "a", edge to drop wires "b" (mismatch → skipped).
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                PortEmittingNode(id = "route"),
                JsonataNode(id = "keep", expression = "email"),
                JsonataNode(id = "drop", expression = "email"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge("e1", "in", "route"),
                PipelineEdge("e2", "route", "keep", sourcePort = "a"),
                PipelineEdge("e3", "route", "drop", sourcePort = "b"),
                PipelineEdge("e4", "keep", "out"),
            ),
        )
        executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertTrue("keep" in trace.outputs, "the matching-port branch ran")
        assertTrue("drop" in trace.skipped, "the mismatched-port branch was skipped (no value reached it)")
    }

    @Test
    fun `a multi-input node wired to an untaken routing branch is skipped even when another input is always-on`() = runTest {
        // route emits "a". "always" runs off route.a; "gated" has TWO inbound edges — one from "always"
        // (an always-on input) and one from route.b (the branch route did NOT take, so "b" is undefined).
        // The bug: "gated" fired because its always-on input arrived; it must be SKIPPED because the
        // routed-branch input it was wired to never arrived (e.g. an "update" action on an "add" node's
        // untaken `alreadyExists` port, whose `properties` input is always present).
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                PortEmittingNode(id = "route"),
                JsonataNode(id = "always", expression = "email"),
                JsonataNode(id = "gated", expression = "email"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge("e1", "in", "route"),
                PipelineEdge("e2", "route", "always", sourcePort = "a"),
                PipelineEdge("e3", "always", "gated"),
                PipelineEdge("e4", "route", "gated", sourcePort = "b"),
                PipelineEdge("e5", "always", "out"),
            ),
        )
        executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertTrue("always" in trace.outputs, "the always-on input ran")
        assertTrue("gated" in trace.skipped, "the node wired to the untaken 'b' branch is skipped despite its always-on input")
        assertTrue("gated" !in trace.outputs, "the gated node must not execute")
    }

    @Test
    fun `a multi-input node whose routed-branch input WAS taken still runs alongside an always-on input`() = runTest {
        // The sibling of the gate test: "gated" is wired to route.a (the branch route DID take) plus an
        // always-on input. The routed branch delivered, so the node runs — branch routing only gates a
        // node whose routed-branch input did not arrive, never one that did (preserves branch-merge).
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                PortEmittingNode(id = "route"),
                JsonataNode(id = "always", expression = "email"),
                JsonataNode(id = "gated", expression = "email"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge("e1", "in", "route"),
                PipelineEdge("e2", "route", "always", sourcePort = "a"),
                PipelineEdge("e3", "always", "gated"),
                PipelineEdge("e4", "route", "gated", sourcePort = "a"),
                PipelineEdge("e5", "gated", "out"),
            ),
        )
        executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertTrue("gated" in trace.outputs, "the node whose routed-branch input was taken runs")
        assertTrue("gated" !in trace.skipped, "a node whose routed branch was taken is not gated")
    }

    // ----------------------------------------------------------------------------------------------
    // Node failure handling — named node, cancellation propagation
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a node failure under a trace records the raw error message into the trace`() = runTest {
        // Covers the runNodeWithReliability catch (e: Exception) arm that writes ctx.trace.errors
        // when trace != null (the named node merely proves a non-blank node.name still routes here).
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val boom = NamedBoomNode(id = "boom", name = "Send Email")
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), boom, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "boom"), PipelineEdge("e2", "boom", "out")),
        )
        assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx)
        }
        // The trace recorded the failing node's own message (the catch arm that writes ctx.trace.errors).
        assertTrue("boom" in (trace.errors["boom"] ?: ""), "the node's error message is traced")
    }

    @Test
    fun `cancellation inside a node propagates without being captured as a node failure`() = runTest {
        val sink = CapturingSink()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, nodeSink = sink, runId = Uuid.random())
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), CancellingNode(id = "cancel"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "cancel"), PipelineEdge("e2", "cancel", "out")),
        )
        // A CancellationException thrown by the node is re-thrown, not swallowed into a FAILED outcome.
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Backoff: the initialDelaySeconds > 0 arm with multiplier + maxDelaySeconds cap
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `retry with a positive backoff delays between attempts and still succeeds`() = runTest {
        FlakyNode.attempts.set(0)
        FlakyNode.succeedOnAttempt = 3 // fail twice → two backoff waits exercise the cap
        val sink = CapturingSink()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, nodeSink = sink, runId = Uuid.random())
        // initialDelaySeconds > 0 with multiplier > 1 and a tiny maxDelaySeconds exercises the
        // min(seconds, maxDelaySeconds) cap on the second backoff. runTest auto-advances virtual time.
        val flaky = FlakyNode(id = "flaky").apply {
            retry = RetryPolicy(maxAttempts = 5, initialDelaySeconds = 1, multiplier = 10.0, maxDelaySeconds = 1)
        }
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), flaky, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "flaky"), PipelineEdge("e2", "flaky", "out")),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertEquals(3, FlakyNode.attempts.get(), "two failures then success")
        assertTrue(result != null)
        val flakyEvents = sink.events.filter { it.nodeId == "flaky" }.map { it.status }
        assertEquals(
            listOf(NodeExecutionStatus.FAILED, NodeExecutionStatus.FAILED, NodeExecutionStatus.OK),
            flakyEvents,
        )
    }

    // ----------------------------------------------------------------------------------------------
    // Test nodes + sinks for the additions above
    // ----------------------------------------------------------------------------------------------

    /** A node that parks the run (durable) or degrades to passing its input through (non-durable). */
    @Serializable
    private class SuspendingTestNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            inputs.first
        override suspend fun run(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): bosca.pipelines.node.NodeResult =
            if (context.runId == null) {
                bosca.pipelines.node.NodeResult.Output(execute(context, inputs))
            } else {
                bosca.pipelines.node.NodeResult.Suspend { enqueued.set(true) }
            }

        companion object {
            const val AWAIT_KEY = "await-unit"
            val enqueued = java.util.concurrent.atomic.AtomicBoolean(false)
        }
    }

    /** An action node that passes its input through and is wired with a rollback pipeline. */
    @Serializable
    private class RollbackTestNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            inputs.first?.toJson(context.json)
    }

    /** A rollback-enabled action node that produces no output (to exercise the null-output rollback arm). */
    @Serializable
    private class NullOutputRollbackNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? = null
    }

    /** A node that emits its (JSON) output on the named port "a" — for ported-edge routing. */
    @Serializable
    private class PortEmittingNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            inputs.first?.toJson(context.json)?.onPort("a")
    }

    /** A node that always throws, carrying an operator-given name (for the name-in-message arm). */
    @Serializable
    private class NamedBoomNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            error("boom")
    }

    /** A node that throws CancellationException — to prove the executor re-throws, not captures, it. */
    @Serializable
    private class CancellingNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            throw kotlinx.coroutines.CancellationException("cancelled mid-node")
    }

    private class CapturingRollbackSink : bosca.pipelines.node.RollbackSink {
        val events = java.util.concurrent.CopyOnWriteArrayList<bosca.pipelines.node.RollbackEvent>()
        override fun record(event: bosca.pipelines.node.RollbackEvent) {
            events.add(event)
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Descriptor-loaded slot enforcement + unhandled-error-port arms.
    //
    // The executor skips slot/descriptor work entirely when no PipelineNodeSerializers modules are
    // loaded (meta.descriptors empty → descriptor == null). Registering a serializers provider with
    // descriptors exercises: the `else` branch of line 140 (typeKey lookup), the typeKey body itself
    // (338), SlotValidator's run-time enforcement + its violation message/trace (147, 149), and the
    // unhandled-error-port guard (175, 178, 180). cachedMeta is per-executor instance, so each of
    // these tests builds a FRESH PipelineExecutorImpl AFTER registering the provider.
    // ----------------------------------------------------------------------------------------------

    @OptIn(InternalDI::class)
    @AfterTest
    fun tearDownRegistry() {
        ProviderRegistry.clear()
    }

    /** Registers a serializers module + descriptors for the descriptor-aware test node, then a fresh executor. */
    private fun registerSlotMeta() {
        provides<PipelineNodeSerializers> { SlotAwareSerializers() }
    }

    @Test
    fun `a node whose required slot gets no value fails the run with a per-port message`() = runTest {
        // The node declares a required "subject" slot. With descriptors loaded, the executor enforces
        // it; an inbound value that violates the STRING kind on a single slot is a violation that
        // fails the run and writes the message into the trace (lines 140, 147, 149, 338).
        registerSlotMeta()
        val freshExecutor = PipelineExecutorImpl()
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                // Person is an OBJECT; the slot demands a STRING → a kind violation.
                StringSlotNode(id = "needs", name = "Needs A String"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "needs"),
                PipelineEdge(id = "e2", source = "needs", target = "out"),
            ),
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            freshExecutor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx)
        }
        assertTrue("rejected its input" in (failure.message ?: ""), "the slot-rejection message surfaces")
        assertTrue("Needs A String" in (failure.message ?: ""), "the operator name is in the message")
        assertTrue("needs" in trace.errors, "the violation was recorded into the trace")
    }

    @Test
    fun `a node whose input satisfies its declared slot runs normally`() = runTest {
        // The other arm of the slot check: the inbound value satisfies the STRING slot, so the
        // descriptor is looked up (140/338) and SlotValidator returns no violations — the node runs.
        registerSlotMeta()
        val freshExecutor = PipelineExecutorImpl()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "JSON"),
                StringSlotNode(id = "ok"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "ok"),
                PipelineEdge(id = "e2", source = "ok", target = "out"),
            ),
        )
        val result = freshExecutor.execute(p, PipelineValue.ofJson(JsonPrimitive("hello")), ctx).requireCompleted()
        assertEquals(JsonPrimitive("hello"), result?.value)
    }

    @Test
    fun `a value emitted on a declared error port that nothing handles fails the run`() = runTest {
        // The node declares an error output port "err" and emits on it; nothing is wired to that port,
        // so the run fails (lines 175, 178, 180). The descriptor's outputs say "err" is an error port.
        registerSlotMeta()
        val freshExecutor = PipelineExecutorImpl()
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "JSON"),
                ErrorPortNode(id = "boom", name = "Risky Step"),
                OutputNode(id = "out"),
            ),
            // The success edge is wired; the error port "err" is deliberately NOT.
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "boom"),
                PipelineEdge(id = "e2", source = "boom", target = "out", sourcePort = "ok"),
            ),
        )
        val failure = assertFailsWith<IllegalStateException> {
            freshExecutor.execute(p, PipelineValue.ofJson(JsonPrimitive("x")), ctx)
        }
        assertTrue("unhandled error" in (failure.message ?: ""), "names the unhandled error port")
        assertTrue("err" in (failure.message ?: ""), "names the port that was emitted on")
        assertTrue("boom" in trace.errors, "the unhandled-error message was traced")
    }

    @Test
    fun `a value emitted on a declared error port that IS wired routes down its branch`() = runTest {
        // The other arm of the error-port guard: the same error port IS wired, so the value routes
        // along it instead of failing the run (the `wired` true branch of line 177).
        registerSlotMeta()
        val freshExecutor = PipelineExecutorImpl()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "JSON"),
                ErrorPortNode(id = "boom"),
                OutputNode(id = "out"),
            ),
            // The error port "err" is wired to the output — a handled error.
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "boom"),
                PipelineEdge(id = "e2", source = "boom", target = "out", sourcePort = "err"),
            ),
        )
        val result = freshExecutor.execute(p, PipelineValue.ofJson(JsonPrimitive("x")), ctx).requireCompleted()
        // The error-port value flowed down the wired branch to the output (run did not fail).
        assertEquals(JsonPrimitive("handled"), result?.value)
    }

    // ----------------------------------------------------------------------------------------------
    // gatherInputs: a fan-in node whose one inbound source produced NO value skips that source
    // (the `outputs[edge.source] ?: continue` arm, line 301) but still runs on the other inbound.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a fan-in node ignores an inbound source that produced no value and still runs`() = runTest {
        // in --true--> jxTrue --\
        //   \--false-> jxFalse(skipped → null) --> map        map gathers both inbound edges; the
        // skipped jxFalse contributes nothing (line 301's `?: continue`), but jxTrue's value keeps the
        // map non-empty so it runs (not skipped). ObjectsToMap keys each inbound by its target port.
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                ConditionNode(id = "cond", expression = "email"),
                JsonataNode(id = "jxTrue", expression = "'present'"),
                JsonataNode(id = "jxFalse", expression = "'absent'"),
                ObjectsToMapNode(id = "map"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "cond"),
                PipelineEdge(id = "e2", source = "cond", target = "jxTrue", sourcePort = "true"),
                PipelineEdge(id = "e3", source = "cond", target = "jxFalse", sourcePort = "false"),
                // Both branch results feed the same fan-in node on distinct ports; jxFalse is skipped.
                PipelineEdge(id = "e4", source = "jxTrue", target = "map", targetPort = "yes"),
                PipelineEdge(id = "e5", source = "jxFalse", target = "map", targetPort = "no"),
                PipelineEdge(id = "e6", source = "map", target = "out"),
            ),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        // The map ran (jxFalse's null source was skipped in gatherInputs, jxTrue's value carried it).
        assertTrue("map" in trace.outputs, "the fan-in node ran on the one inbound value it received")
        assertTrue("jxFalse" in trace.skipped, "the untaken branch was skipped, contributing no value")
        assertEquals(JsonPrimitive("present"), (result?.value as? kotlinx.serialization.json.JsonObject)?.get("yes"))
    }

    // ----------------------------------------------------------------------------------------------
    // Descriptor-aware test nodes + serializers (registered only by the descriptor-loaded tests).
    // ----------------------------------------------------------------------------------------------

    /** A node whose descriptor declares a single REQUIRED STRING input slot — for slot enforcement. */
    @Serializable
    @SerialName("slotString")
    private class StringSlotNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            inputs.first
    }

    /** A node that always emits its (JSON) output on a declared ERROR port "err" — for the unhandled-error arm. */
    @Serializable
    @SerialName("errPort")
    private class ErrorPortNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            PipelineValue.ofJson(JsonPrimitive("handled")).onPort("err")
    }

    /** Contributes the descriptor-aware test nodes' serializers + their slot/port descriptors. */
    private class SlotAwareSerializers : PipelineNodeSerializers {
        override val module: SerializersModule = SerializersModule {
            polymorphic(PipelineNode::class) {
                subclass(InputNode::class, InputNode.serializer())
                subclass(OutputNode::class, OutputNode.serializer())
                subclass(StringSlotNode::class, StringSlotNode.serializer())
                subclass(ErrorPortNode::class, ErrorPortNode.serializer())
                subclass(OkPortNode::class, OkPortNode.serializer())
                subclass(UnlistedNode::class, UnlistedNode.serializer())
                subclass(TwoSlotNode::class, TwoSlotNode.serializer())
            }
        }
        override val descriptors: List<NodeDescriptor> = listOf(
            NodeDescriptor(
                key = "slotString",
                label = "String Slot",
                category = NodeCategory.TRANSFORM,
                inputs = listOf(NodeInputSlot(name = "value", typeLabel = "String", kind = SlotKind.STRING, required = true)),
            ),
            NodeDescriptor(
                key = "errPort",
                label = "Error Port",
                category = NodeCategory.ACTION,
                outputs = listOf(
                    NodeOutputSlot(name = "ok"),
                    NodeOutputSlot(name = "err", error = true),
                ),
            ),
            NodeDescriptor(
                key = "okPort",
                label = "Ok Port",
                category = NodeCategory.ACTION,
                // "ok" is a declared NON-error output port — emitting on it must not trip the error guard.
                outputs = listOf(NodeOutputSlot(name = "ok")),
            ),
            NodeDescriptor(
                key = "twoSlot",
                label = "Two Slot",
                category = NodeCategory.TRANSFORM,
                inputs = listOf(
                    NodeInputSlot(name = "left", typeLabel = "Any", kind = SlotKind.ANY),
                    NodeInputSlot(name = "right", typeLabel = "Any", kind = SlotKind.ANY),
                ),
            ),
        )
    }

    @Serializable
    @SerialName("twoSlot")
    private class TwoSlotNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first
    }

    @Test
    fun `a multi-input node skips when one satisfied slot accompanies a skipped dependency`() = runTest {
        registerSlotMeta()
        val freshExecutor = PipelineExecutorImpl()
        val input = PipelineValue.ofJson(JsonPrimitive("left"))
        val trace = DryRunTrace()
        val pipeline = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "JSON"), TwoSlotNode(id = "join")),
            edges = listOf(
                PipelineEdge(id = "left", source = "in", target = "join", targetPort = "left"),
                PipelineEdge(id = "right", source = "skipped-source", target = "join", targetPort = "right"),
            ),
        )
        val state = ExecutionState(
            outputs = mapOf("in" to input, "skipped-source" to null),
            skipped = setOf("skipped-source"),
        )

        freshExecutor.execute(
            pipeline,
            input = null,
            context = PipelineContext(AuthenticationContext(null, null), Json, trace = trace),
            state = state,
        )

        assertTrue("join" in trace.skipped)
        assertTrue(trace.skipped.getValue("join").contains("input came from a skipped node"))
    }

    // ==============================================================================================
    // ADDED COVERAGE — branch arms not exercised above. Each test names the executor lines it drives.
    // ==============================================================================================

    // ----------------------------------------------------------------------------------------------
    // The per-node async lambda ($execute$results$1$1$1) under ALL THREE observers at once
    // (trace + nodeSink + rollbackSink) — drives the emit() arms (port set, output set) plus the
    // encoded?.let trace arm (187) and the rollbackSink.record arm (193) in a single durable run.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a durable run with trace, sink and rollback all set drives the output, port, encode and rollback arms`() = runTest {
        val rollbackId = Uuid.random()
        val trace = DryRunTrace()
        val sink = CapturingSink()
        val rollbackSink = CapturingRollbackSink()
        val ctx = PipelineContext(
            AuthenticationContext(null, null), Json,
            trace = trace,
            inputCreated = bosca.serialization.OffsetDateTime.now(),
            runId = Uuid.random(),
            nodeSink = sink,
            rollbackSink = rollbackSink,
        )
        // act: rollback-enabled action emitting on a NAMED port; its output is encoded once and fed to
        // both the trace (187) and the rollback sink (193), and the OK emit carries the port (188).
        val node = PortRollbackNode(id = "act", name = "Risky Side Effect").apply { rollbackPipeline = rollbackId }
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), node, OutputNode(id = "out")),
            edges = listOf(
                PipelineEdge("e1", "in", "act"),
                // The node emits on port "p"; the edge to out matches it so the value flows.
                PipelineEdge("e2", "act", "out", sourcePort = "p"),
            ),
        )
        executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()

        assertTrue("act" in trace.outputs, "the encoded output reached the trace (line 187)")
        assertEquals("p", sink.events.first { it.nodeId == "act" }.port, "the OK event carries the routed port")
        assertEquals(1, rollbackSink.events.size, "a rollback event was emitted (line 193)")
        assertEquals(rollbackId, rollbackSink.events.single().rollbackPipelineId)
        assertTrue(rollbackSink.events.single().output != null, "the encoded output rode the rollback event")
    }

    // ----------------------------------------------------------------------------------------------
    // A suspending node UNDER A TRACE in a durable run — drives the SUSPENDED emit arm and the
    // fresh-run seed-into-trace arm (line 100) on the suspend path, plus the multi-round readiness
    // re-check (line 112) when the downstream is not ready while the node is parked.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a suspending node under a trace parks and traces the seeded input`() = runTest {
        val trace = DryRunTrace()
        val sink = CapturingSink()
        val ctx = PipelineContext(
            AuthenticationContext(null, null), Json,
            trace = trace, runId = Uuid.random(), nodeSink = sink,
        )
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), SuspendingTestNode(id = "susp"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "susp"), PipelineEdge("e2", "susp", "out")),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx)
        result as bosca.pipelines.service.ExecutionResult.Suspended
        // The seed InputNode output was written into the trace on the fresh-run path (line 100).
        assertTrue("in" in trace.outputs, "the seed input was traced")
        // The parked node got a SUSPENDED timeline event; out (not ready) was never run.
        assertEquals(NodeExecutionStatus.SUSPENDED, sink.events.first { it.nodeId == "susp" }.status)
        assertTrue(sink.events.none { it.nodeId == "out" }, "the not-ready downstream did not run")
    }

    // ----------------------------------------------------------------------------------------------
    // gatherInputs (line 302): a value with a port flows down an edge that has NO sourcePort — the
    // `edge.sourcePort != null` operand of the && short-circuits false, so the value is NOT dropped.
    // (The existing ported test covers the mismatch-drop arm; this covers the keep arm.)
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a ported value flows down an edge with no sourcePort instead of being dropped`() = runTest {
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                PortEmittingNode(id = "route"), // emits on port "a"
                JsonataNode(id = "keep", expression = "email"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge("e1", "in", "route"),
                // No sourcePort on this edge → a ported value still flows (line 302 keep arm).
                PipelineEdge("e2", "route", "keep"),
                PipelineEdge("e3", "keep", "out"),
            ),
        )
        executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertTrue("keep" in trace.outputs, "the ported value flowed down the un-ported edge")
    }

    // ----------------------------------------------------------------------------------------------
    // runNodeWithReliability line 250: timeoutSeconds == 0 → takeIf { it > 0 } is false, so no timeout
    // wraps the run (the it>0 false arm + the takeIf-null arm). A zero timeout means "no timeout".
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a node with a zero timeout runs without any timeout wrapper`() = runTest {
        val ctx = PipelineContext(AuthenticationContext(null, null), Json)
        val node = JsonataNode(id = "jx", expression = "email").apply { timeoutSeconds = 0 }
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), node, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "jx"), PipelineEdge("e2", "jx", "out")),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertEquals(JsonPrimitive("ada@x.io"), result?.value)
    }

    // ----------------------------------------------------------------------------------------------
    // runNodeWithReliability line 264: a NAMED node that times out — the node.name.ifBlank { node.id }
    // non-blank arm of the timeout message (the existing timeout test uses a blank-named node).
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a named node that times out names itself in the timeout error`() = runTest {
        val ctx = PipelineContext(AuthenticationContext(null, null), Json)
        val slow = SlowNode(id = "slow", name = "Fetch Remote").apply { timeoutSeconds = 1 }
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), slow, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "slow"), PipelineEdge("e2", "slow", "out")),
        )
        val failure = assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx)
        }
        assertTrue("Fetch Remote" in (failure.message ?: ""), "the operator name names the timed-out node")
        assertTrue("timed out" in (failure.message ?: ""))
    }

    // ----------------------------------------------------------------------------------------------
    // runNodeWithReliability line 271: a node failure records a FAILED event when a nodeSink is set
    // BUT no trace — proving the sink.record path is independent of the trace (the existing failing
    // tests pair sink with the trace-less ctx; this isolates the sink-only record arm for a NAMED node).
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a failing named node records a sink FAILED event with its name in the message`() = runTest {
        val sink = CapturingSink()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, nodeSink = sink, runId = Uuid.random())
        val boom = NamedBoomNode(id = "b", name = "Charge Card")
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), boom, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "b"), PipelineEdge("e2", "b", "out")),
        )
        assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx)
        }
        val ev = sink.events.first { it.nodeId == "b" }
        assertEquals(NodeExecutionStatus.FAILED, ev.status)
        assertTrue("boom" in (ev.error ?: ""), "the sink event carries the failure message")
    }

    // ----------------------------------------------------------------------------------------------
    // backoffMillis line 283 (initialDelaySeconds <= 0 early-return 0) is already covered by the
    // no-delay retry test; here we cover the cyclic-graph detection with a node that DOES become
    // ready in a later round (line 112 predicate false then true across rounds), under a sink.
    // A 3-node chain where the middle node's downstream is not ready in round 1.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a multi-round chain re-checks readiness across rounds under a sink`() = runTest {
        val sink = CapturingSink()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, nodeSink = sink, runId = Uuid.random())
        // in -> a -> b -> c -> out : each downstream is NOT ready until the prior round completes,
        // so the readiness predicate (line 112) returns false for them early and true later.
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                JsonataNode(id = "a", expression = "email"),
                JsonataNode(id = "b", expression = "$"),
                JsonataNode(id = "c", expression = "$"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge("e1", "in", "a"),
                PipelineEdge("e2", "a", "b"),
                PipelineEdge("e3", "b", "c"),
                PipelineEdge("e4", "c", "out"),
            ),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertEquals(JsonPrimitive("ada@x.io"), result?.value)
        assertEquals(setOf("a", "b", "c", "out"), sink.events.map { it.nodeId }.toSet())
    }

    // ----------------------------------------------------------------------------------------------
    // typeKey / descriptor lookup (lines 140, 338): a node whose @SerialName is NOT among the loaded
    // descriptors — descriptors are non-empty (so line 139 takes the else), typeKey is computed (338,
    // the all-non-null happy path) but meta.descriptors[it] is null, so the descriptor is null and no
    // slot enforcement runs. This drives line 140's `?.let` with a present typeKey but absent descriptor.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a node whose type is not in the loaded descriptors runs with no slot enforcement`() = runTest {
        registerSlotMeta()
        val freshExecutor = PipelineExecutorImpl()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json)
        // UnlistedNode IS registered as a polymorphic subclass (so typeKey can encode it — line 338's
        // all-non-null arm), but its @SerialName "unlisted" is NOT among the loaded descriptors;
        // descriptors are non-empty, so line 139 takes the else and typeKey is read, yet
        // descriptors[typeKey] is null → descriptor null and no slot enforcement runs (line 140).
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "JSON"),
                UnlistedNode(id = "u"),
                OutputNode(id = "out"),
            ),
            edges = listOf(PipelineEdge("e1", "in", "u"), PipelineEdge("e2", "u", "out")),
        )
        val result = freshExecutor.execute(p, PipelineValue.ofJson(JsonPrimitive("payload")), ctx).requireCompleted()
        assertEquals(JsonPrimitive("payload"), result?.value)
    }

    // ----------------------------------------------------------------------------------------------
    // line 175: a node that emits on a NON-error declared port (descriptor present, the port IS in the
    // descriptor's outputs but error=false) — the `any { it.name == port && it.error }` predicate
    // returns false, so the unhandled-error guard does not fire and the value routes normally.
    // Covers the `&& it.error` false arm and the `== true` false arm of line 175.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a value on a declared non-error port routes without tripping the error guard`() = runTest {
        registerSlotMeta()
        val freshExecutor = PipelineExecutorImpl()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json)
        // ErrorPortNode declares ports "ok" (non-error) and "err" (error). Here it emits on "ok"
        // (a non-error port), wired downstream — the error guard's predicate is false for "ok".
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "JSON"),
                OkPortNode(id = "step"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge("e1", "in", "step"),
                PipelineEdge("e2", "step", "out", sourcePort = "ok"),
            ),
        )
        val result = freshExecutor.execute(p, PipelineValue.ofJson(JsonPrimitive("x")), ctx).requireCompleted()
        assertEquals(JsonPrimitive("done"), result?.value)
    }

    // ----------------------------------------------------------------------------------------------
    // line 175 again: a node that emits on a port while NO descriptors are loaded — descriptor == null,
    // so `descriptor?.outputs` short-circuits to null and `== true` is false (the value is treated as a
    // plain non-error port and ends its branch). Covers the descriptor-null arm of the error guard.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a value on a port with no descriptors loaded is not treated as an unhandled error`() = runTest {
        // No registerSlotMeta() → meta.descriptors empty → descriptor null. PortEmittingNode emits on
        // port "a" with nothing wired to it; with no descriptor it is NOT an error port, so the run
        // completes (the un-consumed branch simply ends) instead of failing.
        val ctx = PipelineContext(AuthenticationContext(null, null), Json)
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                PortEmittingNode(id = "route"), // emits on port "a", nothing consumes it
                OutputNode(id = "out"),
            ),
            // out is wired straight off input so the run still completes; route's "a" port is unconsumed.
            edges = listOf(
                PipelineEdge("e1", "in", "route"),
                PipelineEdge("e2", "in", "out"),
            ),
        )
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertEquals(Person("Ada", "ada@x.io"), result?.value)
    }

    // ----------------------------------------------------------------------------------------------
    // line 187 / 193: a rollback-enabled node UNDER A TRACE but with NO node sink — proves the
    // `encoded` computation triggers off rollbackPipeline != null even without a sink, the encoded
    // value reaches the trace (187), and the rollback event still fires (193) with trace-only context.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a rollback node under a trace-only context still encodes to the trace and emits rollback`() = runTest {
        val rollbackId = Uuid.random()
        val trace = DryRunTrace()
        val rollbackSink = CapturingRollbackSink()
        val ctx = PipelineContext(
            AuthenticationContext(null, null), Json,
            trace = trace, runId = Uuid.random(), rollbackSink = rollbackSink,
        )
        val node = RollbackTestNode(id = "act").apply { rollbackPipeline = rollbackId }
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), node, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "act"), PipelineEdge("e2", "act", "out")),
        )
        executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertTrue("act" in trace.outputs, "the rollback node's output was encoded into the trace")
        assertEquals(1, rollbackSink.events.size)
    }

    // ----------------------------------------------------------------------------------------------
    // ADDED test nodes
    // ----------------------------------------------------------------------------------------------

    /** An action node that passes its (JSON) input through, emitted on the named port "p" — for the OK+port+rollback arms. */
    @Serializable
    private class PortRollbackNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            inputs.first?.toJson(context.json)?.onPort("p")
    }

    // ----------------------------------------------------------------------------------------------
    // An ORPHAN node with no inbound edges in `remaining` — drives the `inboundByTarget[node.id]`
    // null arm of orEmpty() (lines 112, 128) and the `inbound.isNotEmpty()` false arm (line 133):
    // a wired-to-nothing source node runs (not skipped) with empty inputs and produces a value.
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `an orphan source node with no inbound edges runs with empty inputs`() = runTest {
        val trace = DryRunTrace()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, trace = trace)
        // `orphan` has NO inbound edge, so its readiness predicate (line 112) sees an empty inbound
        // list (orEmpty over a null map lookup → ready), and the skip guard (line 133) short-circuits
        // on inbound.isEmpty()==true → it RUNS with empty inputs rather than being skipped.
        val p = pipeline(
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Person"),
                JsonataNode(id = "jx", expression = "email"),
                ConstantSourceNode(id = "orphan"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge("e1", "in", "jx"),
                PipelineEdge("e2", "jx", "out"),
                // orphan has NO inbound edge at all.
            ),
        )
        executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertTrue("orphan" in trace.outputs, "the orphan ran with empty inputs (not skipped)")
        assertTrue("orphan" !in trace.skipped, "an inbound-less node is never skipped")
    }

    // ----------------------------------------------------------------------------------------------
    // A node throwing an exception whose message is NULL, under a trace AND a sink — drives the
    // `e.message ?: e.toString()` toString fallback in both the trace-write (line 164) and the
    // sink FAILED event (line 271).
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a node throwing a null-message exception falls back to toString in the trace and sink`() = runTest {
        val trace = DryRunTrace()
        val sink = CapturingSink()
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, trace = trace, nodeSink = sink, runId = Uuid.random())
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), NullMessageBoomNode(id = "boom"), OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "boom"), PipelineEdge("e2", "boom", "out")),
        )
        assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx)
        }
        // message was null → both the trace and the sink recorded the exception's toString() instead.
        val traced = trace.errors["boom"] ?: ""
        assertTrue(traced.isNotBlank(), "the trace recorded the toString fallback")
        assertTrue("IllegalStateException" in traced, "the toString form names the exception type")
        val ev = sink.events.first { it.nodeId == "boom" }
        assertTrue("IllegalStateException" in (ev.error ?: ""), "the sink event recorded the toString fallback")
    }

    // ----------------------------------------------------------------------------------------------
    // A rollback-enabled node whose context has NO rollback sink — drives the `ctx.rollbackSink?`
    // null arm of line 193 (the rollback pipeline is declared but there is nowhere to emit the event).
    // ----------------------------------------------------------------------------------------------

    @Test
    fun `a rollback node with no rollback sink completes without emitting`() = runTest {
        val ctx = PipelineContext(AuthenticationContext(null, null), Json, runId = Uuid.random()) // no rollbackSink
        val node = RollbackTestNode(id = "act").apply { rollbackPipeline = Uuid.random() }
        val p = pipeline(
            nodes = listOf(InputNode(id = "in", acceptedType = "Person"), node, OutputNode(id = "out")),
            edges = listOf(PipelineEdge("e1", "in", "act"), PipelineEdge("e2", "act", "out")),
        )
        // rollbackPipeline != null but rollbackSink == null → line 193's null-safe arm; run still completes.
        val result = executor.execute(p, PipelineValue.of(Person("Ada", "ada@x.io"), Person.serializer()), ctx).requireCompleted()
        assertTrue(result != null)
    }

    /** A source node with no inputs that always produces a constant JSON value. */
    @Serializable
    private class ConstantSourceNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            PipelineValue.ofJson(JsonPrimitive("constant"))
    }

    /** A node that throws an exception carrying a null message — to exercise the toString() fallbacks. */
    @Serializable
    private class NullMessageBoomNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            throw IllegalStateException() // null message → message ?: toString()
    }

    /** A registered-but-undescribed node: known to the polymorphic module, absent from the descriptors. */
    @Serializable
    @SerialName("unlisted")
    private class UnlistedNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            inputs.first
    }

    /** A descriptor-aware action node that emits on its declared NON-error port "ok". */
    @Serializable
    @SerialName("okPort")
    private class OkPortNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: bosca.pipelines.node.NodePosition = bosca.pipelines.node.NodePosition(),
    ) : bosca.pipelines.node.ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: bosca.pipelines.node.NodeInputs): PipelineValue? =
            PipelineValue.ofJson(JsonPrimitive("done")).onPort("ok")
    }
}
