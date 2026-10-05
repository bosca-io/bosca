package bosca.pipelines.builtin

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunResultStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action: **parks the run until an external actor supplies a value**, then resumes with that value as
 * the node's output. Unlike [ExecuteJobNode]'s await (which waits on a background job), nothing is
 * enqueued — the run stays SUSPENDED until `Mutation.pipelines.provideInput(runId, nodeId, value)`
 * (authorized by the pipeline's EXECUTE permission) supplies the value and resumes it. Parking
 * requires a durable run (a [PipelineContext.runId]); a non-durable or dry run has nothing to park
 * on and passes the inbound value through unchanged.
 *
 * This is the human-in-the-loop primitive. An **approval** is a `WaitForInput` whose value is a
 * decision, with a downstream Condition/Switch routing approve→continue / reject→rollback; a form
 * submission, a chosen option, or a supplied config value are the same node. The wait is bounded by
 * the run service's stuck-suspended sweep (a global max lifetime), so a value that never arrives
 * eventually fails the run.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Wait for Input",
    description = "Parks the run until an external actor provides a value (via provideInput), then continues with that value as the node's output.",
    group = "Core",
    subgroup = "Gates & Timing",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Passthrough",
            description = "Optional inbound value; it is the node's output only until a value is provided, which replaces it.",
            required = false,
        ),
    ],
    settings = [
        SettingSlot(
            name = "prompt", control = SettingControl.TEXT, label = "Prompt",
            placeholder = "What input is needed?",
            description = "Human-readable description of the input being waited on, shown wherever the parked run is surfaced.",
        ),
    ],
)
@Serializable
@SerialName("waitForInput")
class WaitForInputNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** Human-readable description of the input being waited on. */
    val prompt: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean get() = true

    override suspend fun doSuspend(context: PipelineContext, inputs: NodeInputs) {
        val runId = context.runId ?: return
        // Stage the inbound value as a placeholder output; provideInput overwrites it with the supplied
        // value before resuming. Nothing is enqueued — the run parks until that external resume, so a
        // value MUST be provided (or the stuck-suspended sweep eventually fails it).
        provide<PipelineRunResultStore>().put(runId, id, inputs.first?.encode(context.json) ?: JsonNull)
    }

    /**
     * Reached only when the run is not durable (no [PipelineContext.runId] to resume) — there is
     * nothing to park on, so, per the [run] contract's non-durable fallback, pass the inbound value
     * through unchanged (the same value the durable resume would emit on success).
     */
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(
            id,
            buildJsonObject {
                put("action", "waitForInput")
                put("prompt", prompt)
            },
        )
        return inputs.first
    }
}
