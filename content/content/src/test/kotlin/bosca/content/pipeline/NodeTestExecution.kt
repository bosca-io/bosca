package bosca.content.pipeline

import bosca.pipelines.PipelineContext
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineValue

/**
 * Drives a node through its real entry point ([PipelineNode.run]) and returns the synchronous output,
 * asserting the node did NOT suspend — its [run] produced a [NodeResult.Output], not a
 * [NodeResult.Suspend].
 *
 * This is the unit-test seam for a content node's transform/action logic. [PipelineNode.execute] is
 * `protected` precisely so a test cannot bypass [run] (and with it the durable suspend/resume
 * decision); a node that unexpectedly parks fails loudly here instead of silently skipping its work.
 *
 * (Mirrors `bosca.pipelines.testutil.executeForTest`, which lives in the pipelines test source set and
 * is therefore not visible to this module's tests.)
 */
suspend fun PipelineNode.executeForTest(context: PipelineContext, inputs: NodeInputs): PipelineValue? =
    when (val result = run(context, inputs)) {
        is NodeResult.Output -> result.value
        is NodeResult.Suspend -> error(
            "Node '${name.ifBlank { id }}' suspended under executeForTest — this seam is for synchronous " +
                "content nodes; test suspension by calling run() directly and asserting on NodeResult.Suspend",
        )
    }

/**
 * Like [executeForTest], but for a content node that always produces a value (a transform or routing
 * node): returns the non-null [PipelineValue], failing the test if the node produced no output. Lets a
 * test chain `.value`/`.port` without a null check.
 */
suspend fun PipelineNode.executeForTestValue(context: PipelineContext, inputs: NodeInputs): PipelineValue =
    executeForTest(context, inputs) ?: error("Node '${name.ifBlank { id }}' produced no output")
