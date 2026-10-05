package bosca.pipelines

import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.ExecutionResult
import bosca.pipelines.service.ExecutionState
import bosca.pipelines.service.requireCompleted
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PipelineResumeTest {

    /**
     * A node that parks a durable run (returns [NodeResult.Suspend]) and falls back to passing its
     * input through synchronously when the run is not durable ([PipelineContext.runId] is null).
     */
    @Serializable
    @SerialName("testSuspend")
    private class SuspendingNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first

        override suspend fun run(context: PipelineContext, inputs: NodeInputs): NodeResult =
            if (context.runId == null) NodeResult.Output(inputs.first)
            else NodeResult.Suspend { /* enqueue backing job — irrelevant to this test */ }

        companion object {
            const val AWAIT_KEY = "await-1"
        }
    }

    private val executor = PipelineExecutorImpl()

    // in --> susp --> out
    private fun pipeline() = Pipeline(
        id = Uuid.random(),
        name = "resumable",
        acceptedInputType = "String",
        nodes = listOf(
            InputNode(id = "in", acceptedType = "String"),
            SuspendingNode(id = "susp"),
            OutputNode(id = "out"),
        ),
        edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "susp"),
            PipelineEdge(id = "e2", source = "susp", target = "out"),
        ),
    )

    private fun durableContext() =
        PipelineContext(AuthenticationContext(null, null), Json, runId = UUID.random())

    @Test
    fun `a suspending node parks the run with a checkpoint, without completing`() = runTest {
        val p = pipeline()
        val result = executor.execute(p, PipelineValue.of("seed", String.serializer()), durableContext())

        assertTrue(result is ExecutionResult.Suspended, "expected the run to suspend")
        result as ExecutionResult.Suspended
        assertEquals(1, result.newlyParked.size)
        assertEquals("susp", result.newlyParked.single().nodeId)
        assertTrue("susp" in result.state.awaiting, "the parked node should be in the awaiting set")
        // The upstream Input is checkpointed; the suspended node is NOT (its output is supplied on resume).
        assertTrue("in" in result.state.outputs, "input should be checkpointed")
        assertTrue("susp" !in result.state.outputs, "the suspended node must not be in the checkpoint")
        assertTrue("out" !in result.state.outputs, "downstream of the suspend must not have run")
    }

    @Test
    fun `re-entering with the suspended node's resolved output resumes and completes`() = runTest {
        val p = pipeline()
        val ctx = durableContext()
        val suspended = executor.execute(p, PipelineValue.of("seed", String.serializer()), ctx) as ExecutionResult.Suspended

        // Simulate the resume recording the backing work's result as the parked node's output.
        val resumed = ExecutionState(
            suspended.state.outputs + ("susp" to PipelineValue.ofJson(JsonPrimitive("resolved")))
        )
        val result = executor.execute(p, input = null, context = ctx, state = resumed)

        assertTrue(result is ExecutionResult.Completed, "expected the resumed run to complete")
        assertEquals(JsonPrimitive("resolved"), (result as ExecutionResult.Completed).output?.value)
    }

    @Test
    fun `a node already in the checkpoint is never re-run on resume`() = runTest {
        // If "susp" is present in the state, the executor must not call into it again — it would
        // otherwise re-suspend forever. Reaching Completed proves it was treated as done.
        val p = pipeline()
        val resumed = ExecutionState(
            mapOf(
                "in" to PipelineValue.ofJson(JsonPrimitive("seed")),
                "susp" to PipelineValue.ofJson(JsonPrimitive("resolved")),
            )
        )
        val result = executor.execute(p, input = null, context = durableContext(), state = resumed)
        assertEquals(JsonPrimitive("resolved"), (result as ExecutionResult.Completed).output?.value)
    }

    @Test
    fun `a fan-out parks both branches and completes after both resume in any order`() = runTest {
        // in --> A (suspend) --> out
        //   \--> B (suspend) --/   (out joins on A and B)
        val p = Pipeline(
            id = Uuid.random(),
            name = "fan-out",
            acceptedInputType = "String",
            nodes = listOf(
                InputNode(id = "in", acceptedType = "String"),
                SuspendingNode(id = "A"),
                SuspendingNode(id = "B"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "A"),
                PipelineEdge(id = "e2", source = "in", target = "B"),
                PipelineEdge(id = "e3", source = "A", target = "out"),
                PipelineEdge(id = "e4", source = "B", target = "out"),
            ),
        )
        val ctx = durableContext()

        // Round 1: both branches park at once.
        val first = executor.execute(p, PipelineValue.of("seed", String.serializer()), ctx) as ExecutionResult.Suspended
        assertEquals(setOf("A", "B"), first.newlyParked.mapTo(mutableSetOf()) { it.nodeId })
        assertEquals(setOf("A", "B"), first.state.awaiting)

        // Resume B first: A is still parked (not re-run), out can't run yet.
        val afterB = executor.execute(
            p, null, ctx,
            ExecutionState(first.state.outputs + ("B" to PipelineValue.ofJson(JsonPrimitive("rb"))), awaiting = setOf("A")),
        ) as ExecutionResult.Suspended
        assertTrue(afterB.newlyParked.isEmpty(), "no node should re-park; A was already parked")
        assertEquals(setOf("A"), afterB.state.awaiting)

        // Resume A: both branches resolved, out joins and the run completes.
        val done = executor.execute(
            p, null, ctx,
            ExecutionState(afterB.state.outputs + ("A" to PipelineValue.ofJson(JsonPrimitive("ra"))), awaiting = emptySet()),
        )
        assertTrue(done is ExecutionResult.Completed, "the run should complete once both branches resume")
    }

    @Test
    fun `a non-durable run cannot suspend - the node falls back to synchronous output`() = runTest {
        val p = pipeline()
        // No runId on the context -> SuspendingNode passes its input through instead of suspending.
        val nonDurable = PipelineContext(AuthenticationContext(null, null), Json)
        val result = executor.execute(p, PipelineValue.of("seed", String.serializer()), nonDurable)
        // The input flows through unchanged, carrying its original typed value (not JSON).
        assertEquals("seed", result.requireCompleted()?.value)
    }

    @Test
    fun `requireCompleted throws when a run suspends in a non-durable caller`() = runTest {
        val p = pipeline()
        val error = assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of("seed", String.serializer()), durableContext()).requireCompleted()
        }
        assertTrue(error.message!!.contains("suspended"), error.message!!)
    }
}
