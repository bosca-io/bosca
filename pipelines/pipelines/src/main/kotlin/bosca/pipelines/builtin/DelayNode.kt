package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

/**
 * Timer: parks a durable run for [delaySeconds], then resumes it with the **inbound value passed
 * through unchanged** on the implicit output. Unlike a thread sleep this holds no worker — the wait
 * is realized as a durable scheduled resume, so it survives worker
 * restarts and is not bound by the runner's per-job time cap.
 *
 * The mechanism reuses the durable suspend/resume machine end to end: the node returns
 * [NodeResult.Suspend], the executor durably checkpoints the run, then the suspend thunk
 *  1. stages the inbound value in the result store (keyed by run/node) so resume emits it,
 *  2. enqueues a [bosca.pipelines.trigger.PipelineDelayJob] as a **child of the run job** for
 *     `now + delaySeconds` (see [durableTimerSuspend]). That delay child re-queues itself until the
 *     wake time, then completes; its completion bubbles up to the run job, whose drive listener
 *     resumes this node from its checkpoint.
 *
 * Non-durable runs (no [PipelineContext.runId] to resume) and dry runs pass the value straight
 * through with no real wait — there is nothing to park. A non-positive [delaySeconds] is likewise an
 * immediate pass-through (no point parking for zero time).
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Delay",
    description = "Pauses the run for a fixed duration, then continues with the inbound value unchanged — durable, holds no worker.",
    group = "Core",
    subgroup = "Gates & Timing",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Value",
            description = "The value passed through unchanged after the delay elapses.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "delaySeconds", control = SettingControl.INTEGER, label = "Delay (seconds)", default = "0", placeholder = "0",
            description = "Pauses the run for this long, then continues with the inbound value unchanged. Durable — the wait survives a worker restart and holds no worker.",
        ),
    ],
)
@Serializable
@SerialName("delay")
class DelayNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** How long to park the run before resuming. Non-positive means continue immediately. */
    val delaySeconds: Long = 0,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    /**
     * Durable + positive delay → park and schedule a self-resume. Everything else (dry, non-durable,
     * or zero/negative delay) falls back to the synchronous pass-through in [execute].
     */
    override suspend fun run(context: PipelineContext, inputs: NodeInputs): NodeResult {
        val runId = context.runId
        if (!context.dryRun && runId != null && delaySeconds > 0) {
            return durableTimerSuspend(context, id, inputs.first, delaySeconds.seconds)
        }
        return NodeResult.Output(execute(context, inputs))
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        if (context.dryRun) {
            context.trace?.recordAction(id, buildJsonObject {
                put("action", "delay")
                put("delaySeconds", delaySeconds)
            })
        }
        // A timer is a pass-through gate, not a sink: continue the run with the inbound value.
        return inputs.first
    }
}
