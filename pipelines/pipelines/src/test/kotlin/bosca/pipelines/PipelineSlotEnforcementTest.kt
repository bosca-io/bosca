package bosca.pipelines

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeInputSlot
import bosca.pipelines.node.NodeOutputSlot
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.pipelines.service.requireCompleted
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * End-to-end runtime enforcement: a registered node type declaring an INTEGER input slot rejects a
 * string at run time but accepts an int. Exercises the full glue — registry lookup, the node-aware
 * `Json` discriminator read, and [bosca.pipelines.node.SlotValidator] — not just the validator.
 */
class PipelineSlotEnforcementTest {

    /** A node declaring (via the registered descriptor below) a single INTEGER input; passes it through. */
    @Serializable
    @SerialName("testIntNode")
    private class IntSlotNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first
    }

    /** An action node that always emits its inbound value on the declared error port "failed". */
    @Serializable
    @SerialName("testFailing")
    private class FailingActionNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? =
            inputs.first?.onPort("failed")
    }

    /** Routes its inbound int to the "yes" port when non-zero, else "no" — a passthrough router. */
    @Serializable
    @SerialName("testRouter")
    private class RouterNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
            val v = inputs.first ?: return null
            return v.onPort(if ((v.value as? Int ?: 0) != 0) "yes" else "no")
        }
    }

    /** A passthrough transform, used as the node gated behind a router branch. */
    @Serializable
    @SerialName("testPass")
    private class PassNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : TransformNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first
    }

    /** A multi-input action with an OPTIONAL `a` and a `b`; emits `b` when it runs. */
    @Serializable
    @SerialName("testMerge")
    private class MergeNode(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs["b"]
    }

    private val context = PipelineContext(AuthenticationContext(null, null), Json)
    private val executor = PipelineExecutorImpl()

    @OptIn(InternalDI::class)
    private fun registerIntSlotNode() {
        val serializers = object : PipelineNodeSerializers {
            override val module = SerializersModule {
                polymorphic(PipelineNode::class) {
                    subclass(InputNode::class, InputNode.serializer())
                    subclass(OutputNode::class, OutputNode.serializer())
                    subclass(IntSlotNode::class, IntSlotNode.serializer())
                }
            }
            override val descriptors = listOf(
                NodeDescriptor(
                    key = "testIntNode",
                    label = "Int",
                    category = NodeCategory.TRANSFORM,
                    inputs = listOf(NodeInputSlot(name = "value", typeLabel = "integer", kind = SlotKind.INTEGER)),
                ),
            )
        }
        provides<PipelineNodeSerializers> { serializers }
    }

    @OptIn(InternalDI::class)
    private fun registerFailingNode() {
        val serializers = object : PipelineNodeSerializers {
            override val module = SerializersModule {
                polymorphic(PipelineNode::class) {
                    subclass(InputNode::class, InputNode.serializer())
                    subclass(OutputNode::class, OutputNode.serializer())
                    subclass(FailingActionNode::class, FailingActionNode.serializer())
                }
            }
            override val descriptors = listOf(
                NodeDescriptor(
                    key = "testFailing",
                    label = "Failing",
                    category = NodeCategory.ACTION,
                    outputs = listOf(
                        NodeOutputSlot(name = "out", kind = SlotKind.ANY, error = false),
                        NodeOutputSlot(name = "failed", kind = SlotKind.OBJECT, error = true),
                    ),
                ),
            )
        }
        provides<PipelineNodeSerializers> { serializers }
    }

    @OptIn(InternalDI::class)
    private fun registerCascadeNodes() {
        val serializers = object : PipelineNodeSerializers {
            override val module = SerializersModule {
                polymorphic(PipelineNode::class) {
                    subclass(InputNode::class, InputNode.serializer())
                    subclass(OutputNode::class, OutputNode.serializer())
                    subclass(RouterNode::class, RouterNode.serializer())
                    subclass(PassNode::class, PassNode.serializer())
                    subclass(MergeNode::class, MergeNode.serializer())
                }
            }
            override val descriptors = listOf(
                NodeDescriptor(
                    key = "testRouter", label = "Router", category = NodeCategory.ROUTE,
                    outputs = listOf(NodeOutputSlot(name = "yes"), NodeOutputSlot(name = "no")),
                ),
                NodeDescriptor(
                    key = "testPass", label = "Pass", category = NodeCategory.TRANSFORM,
                    inputs = listOf(NodeInputSlot(name = "in", typeLabel = "any")),
                ),
                NodeDescriptor(
                    key = "testMerge", label = "Merge", category = NodeCategory.ACTION,
                    inputs = listOf(
                        // `a` is OPTIONAL — the cascade still gates on it being left undefined by a skip.
                        NodeInputSlot(name = "a", typeLabel = "a", required = false),
                        NodeInputSlot(name = "b", typeLabel = "b"),
                    ),
                ),
            )
        }
        provides<PipelineNodeSerializers> { serializers }
    }

    /** in → router → (yes) gated; merge requires `a` (from gated) and `b` (from input); merge → out. */
    private fun cascadePipeline() = Pipeline(
        id = Uuid.random(),
        name = "test",
        acceptedInputType = "Int",
        nodes = listOf(
            InputNode(id = "in", acceptedType = "Int"),
            RouterNode(id = "router"),
            PassNode(id = "gated"),
            MergeNode(id = "merge"),
            OutputNode(id = "out"),
        ),
        edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "router"),
            PipelineEdge(id = "e2", source = "router", target = "gated", sourcePort = "yes"),
            PipelineEdge(id = "e3", source = "gated", target = "merge", targetPort = "a"),
            PipelineEdge(id = "e4", source = "in", target = "merge", targetPort = "b"),
            PipelineEdge(id = "e5", source = "merge", target = "out"),
        ),
    )

    @Test
    fun `skip cascades — a node whose input port came from a skipped node skips too`() = runTest {
        registerCascadeNodes()
        // Falsy input: the router takes "no", so `gated` is skipped. `merge`'s `a` is fed only by the
        // skipped `gated` and is left undefined — so `merge` skips too, even though `a` is OPTIONAL and
        // its other input `b` did arrive. The skip cascades to `out`, so the run completes with nothing.
        val result = executor.execute(cascadePipeline(), PipelineValue.of(0, Int.serializer()), context).requireCompleted()
        assertEquals(null, result)
    }

    @Test
    fun `the dependent runs normally when its branch is taken`() = runTest {
        registerCascadeNodes()
        // Truthy input: the router takes "yes", `gated` runs, `merge` gets `a` (and `b`) and emits `b`.
        val result = executor.execute(cascadePipeline(), PipelineValue.of(5, Int.serializer()), context).requireCompleted()
        assertEquals(JsonPrimitive(5), result?.encode(Json))
    }

    @AfterTest
    @OptIn(InternalDI::class)
    fun tearDown() = ProviderRegistry.clear()

    private fun pipeline() = Pipeline(
        id = Uuid.random(),
        name = "test",
        acceptedInputType = "Int",
        nodes = listOf(
            InputNode(id = "in", acceptedType = "Int"),
            IntSlotNode(id = "n"),
            OutputNode(id = "out"),
        ),
        edges = listOf(
            PipelineEdge(id = "e1", source = "in", target = "n"),
            PipelineEdge(id = "e2", source = "n", target = "out"),
        ),
    )

    @Test
    fun `an int satisfies an INTEGER slot`() = runTest {
        registerIntSlotNode()
        val result = executor.execute(pipeline(), PipelineValue.of(5, Int.serializer()), context).requireCompleted()
        assertEquals(JsonPrimitive(5), result?.encode(Json))
    }

    @Test
    fun `a string fed to an INTEGER slot fails the run with a clear message`() = runTest {
        registerIntSlotNode()
        val error = assertFailsWith<IllegalArgumentException> {
            executor.execute(pipeline(), PipelineValue.of("five", String.serializer()), context)
        }
        assertTrue(error.message!!.contains("rejected its input"), error.message!!)
        assertTrue(error.message!!.contains("expected integer"), error.message!!)
    }

    @Test
    fun `an emitted error port with no wiring fails the run`() = runTest {
        registerFailingNode()
        val p = Pipeline(
            id = Uuid.random(),
            name = "test",
            acceptedInputType = "Int",
            nodes = listOf(InputNode(id = "in", acceptedType = "Int"), FailingActionNode(id = "f")),
            edges = listOf(PipelineEdge(id = "e1", source = "in", target = "f")),
        )
        val error = assertFailsWith<IllegalStateException> {
            executor.execute(p, PipelineValue.of(5, Int.serializer()), context)
        }
        assertTrue(error.message!!.contains("unhandled error port 'failed'"), error.message!!)
    }

    @Test
    fun `a wired error port routes the failure to its branch instead of failing the run`() = runTest {
        registerFailingNode()
        // in --> f (emits on "failed") --[sourcePort=failed]--> out
        val p = Pipeline(
            id = Uuid.random(),
            name = "test",
            acceptedInputType = "Int",
            nodes = listOf(
                InputNode(id = "in", acceptedType = "Int"),
                FailingActionNode(id = "f"),
                OutputNode(id = "out"),
            ),
            edges = listOf(
                PipelineEdge(id = "e1", source = "in", target = "f"),
                PipelineEdge(id = "e2", source = "f", target = "out", sourcePort = "failed"),
            ),
        )
        val result = executor.execute(p, PipelineValue.of(5, Int.serializer()), context).requireCompleted()
        assertEquals(JsonPrimitive(5), result?.encode(Json))
    }
}
