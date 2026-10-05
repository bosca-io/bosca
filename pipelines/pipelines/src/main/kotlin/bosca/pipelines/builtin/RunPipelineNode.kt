package bosca.pipelines.builtin

import bosca.di.provide
import bosca.pipelines.DryRunTrace
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.model.inputNode
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.JsonSchemaValidator
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineExecutor
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.pipelines.service.requireCompleted
import bosca.serialization.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action: runs another stored pipeline ([pipelineId]) inline, feeding it the inbound value as its
 * input, and outputs the sub-pipeline's Output-node value (or nothing when it has none) — so
 * pipelines compose like function calls. The sub-pipeline runs under the same principal, dry-run
 * mode and `inputCreated` clock as this run, but with **isolated scratch attributes** (a
 * sub-pipeline is a call, not a shared workspace). A failing sub-pipeline node fails this run.
 *
 * Dry runs descend into the sub-pipeline: its transform nodes run for real and its action nodes
 * skip their side effects themselves, exactly as in the parent graph. The child run records into
 * its **own** trace, which is merged back into the parent's under `"<this node's id>/<child node
 * id>"` keys — child node ids routinely collide with parent ids (every graph's entry node is
 * `input`), so bare-id merging would overwrite parent entries.
 *
 * [pipelineId] is nullable so a work-in-progress graph with a freshly added, not-yet-targeted Run
 * Pipeline node still saves; executing such a node fails the run with a clear error.
 *
 * Runaway composition (cross-pipeline cycles, runaway nesting) is guarded by the executor itself
 * via [PipelineContext.invocationStack] — this node just passes the chain through; the executor
 * appends the child pipeline's id and aborts before executing any of its nodes.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Run Pipeline",
    description = "Runs another pipeline with the inbound value as its input and outputs that pipeline's output.",
    group = "Core",
    subgroup = "Flow",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Input",
            description = "The value passed as the sub-pipeline's input (validated against its input schema); the sub-pipeline's output becomes this node's output.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "pipelineId", control = SettingControl.REFERENCE, reference = ReferenceSource.PIPELINE,
            label = "Pipeline", placeholder = "Pick a pipeline…",
            description = "Runs the picked pipeline with the inbound value as its input; its output flows to downstream nodes. A failure in the picked pipeline fails this run.",
        ),
    ],
)
@Serializable
@SerialName("runPipeline")
class RunPipelineNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val pipelineId: UUID? = null,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    /**
     * Durable run (a real `runId`, not dry) → run the target as a **durable child run** and park this
     * run until it finishes, so a child that itself suspends (a timer) keeps the whole
     * composition durable instead of degrading to synchronous. The child's
     * Output value becomes this node's output on resume; a child failure fails this run. Dry and
     * non-durable runs take the synchronous in-line [execute] path (which also descends the dry-run
     * trace) — an inline child can't suspend, exactly as before.
     */
    override suspend fun run(context: PipelineContext, inputs: NodeInputs): NodeResult {
        val runId = context.runId
        if (context.dryRun || runId == null) return NodeResult.Output(execute(context, inputs))

        val input = inputs.first ?: error("Run Pipeline node '${name.ifBlank { id }}' requires an input")
        val pid = pipelineId ?: error("Run Pipeline node '${name.ifBlank { id }}' has no target pipeline selected")
        // The child run loads the target + validates the input against its schema (in runChildPipeline),
        // so the node only parks and hands off — no second pipeline load here.
        val runJob = context.runJob
        val runJobId = context.runJobId
        return NodeResult.Suspend {
            provide<PipelineRunService>().runChildPipeline(runId, id, pid, input, context.inputCreated, runJob, runJobId)
        }
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = inputs.first ?: error("Run Pipeline node '${name.ifBlank { id }}' requires an input")
        val pipelineId = pipelineId
            ?: error("Run Pipeline node '${name.ifBlank { id }}' has no target pipeline selected")
        val pipeline = provide<PipelineService>().get(pipelineId)
            ?: error("Run Pipeline node '${name.ifBlank { id }}': pipeline not found: $pipelineId")
        pipeline.inputNode?.schema?.let { schema ->
            val violations = JsonSchemaValidator.validate(input.encode(context.json), schema)
            check(violations.isEmpty()) {
                "Run Pipeline node '${name.ifBlank { id }}': input does not match pipeline " +
                    "'${pipeline.name}' input schema — ${violations.joinToString("; ")}"
            }
        }
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "runPipeline")
            put("pipelineId", pipelineId.toString())
            put("pipelineName", pipeline.name)
        })
        // The child records into a fresh trace: trace maps are keyed by bare node id and child ids
        // collide with parent ids (every entry node is `input`). Merged back below under
        // "<this node's id>/<child node id>" keys, success or failure.
        val childTrace = context.trace?.let { DryRunTrace() }
        val childContext = PipelineContext(
            context.authentication,
            context.json,
            context.dryRun,
            childTrace,
            context.inputCreated,
            context.invocationStack,
        )
        try {
            return provide<PipelineExecutor>().execute(pipeline, input, childContext).requireCompleted()
        } finally {
            val trace = context.trace
            if (trace != null && childTrace != null) {
                childTrace.outputs.forEach { (nodeId, value) -> trace.outputs["$id/$nodeId"] = value }
                childTrace.actions.forEach { (nodeId, value) -> trace.actions["$id/$nodeId"] = value }
                childTrace.errors.forEach { (nodeId, value) -> trace.errors["$id/$nodeId"] = value }
                childTrace.skipped.forEach { (nodeId, value) -> trace.skipped["$id/$nodeId"] = value }
            }
        }
    }
}
