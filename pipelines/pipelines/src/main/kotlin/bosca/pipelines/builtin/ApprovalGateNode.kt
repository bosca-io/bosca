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
 * the injectable **approval gate**: parks the run until a person decides whether to
 * continue. Place one before any consequential step (a deploy, an environment promotion) — the run stays
 * SUSPENDED at the gate until `Mutation.pipelines.resolveGate(runId, nodeId, approved, note)`:
 *
 *  - **approved** — the run resumes and the gate emits its **original inbound value unchanged**, so the
 *    typed chain through the gate is preserved (a gated `ArtifactPublication` is still an
 *    `ArtifactPublication` downstream). Mechanically: [doSuspend] stages the inbound value as the node's
 *    output and an approval resumes *without overwriting it* — unlike [WaitForInputNode], whose caller
 *    replaces the staged value with the supplied one.
 *  - **rejected** — the run FAILS at the gate with the rejection note; nothing downstream executes.
 *
 * A gate waits **indefinitely** — it is waiting on a person, so the stuck-suspended sweep explicitly
 * exempts gate-parked runs (unlike [WaitForInputNode], whose machine-oriented wait stays bounded).
 * A non-durable or dry run has nothing to park on and passes through.
 */
@PipelineNodeType(
    // ROUTE: the gate is transparent to the type system — it emits its input unchanged on the taken
    // (approved) path, exactly like Condition/Switch, so the validator threads the flowing type through.
    category = NodeCategory.ROUTE,
    label = "Approval Gate",
    description = "Parks the run until someone approves. Approval passes the inbound value through unchanged; rejection fails the run.",
    group = "Core",
    subgroup = "Gates & Timing",
    inputs = [
        InputSlot(
            name = "in", typeLabel = "Value",
            description = "The value flowing through the gate — emitted unchanged when approved.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "prompt", control = SettingControl.TEXT, label = "What is being approved",
            placeholder = "e.g. Promote this release to production?",
            description = "Shown to the approver alongside the parked run.",
        ),
    ],
)
@Serializable
@SerialName("gate.approval")
class ApprovalGateNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** Human-readable description of the decision, shown to the approver. */
    val prompt: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean get() = true

    override suspend fun doSuspend(context: PipelineContext, inputs: NodeInputs) {
        val runId = context.runId ?: return
        // Stage the inbound value as the node's output. An approval resumes WITHOUT overwriting it, so
        // the gate emits exactly what flowed in; a rejection resumes the run as failed instead.
        provide<PipelineRunResultStore>().put(runId, id, inputs.first?.encode(context.json) ?: JsonNull)
    }

    /**
     * Reached only when the run is not durable (no [PipelineContext.runId] to park on) — per the [run]
     * contract's non-durable fallback, pass the inbound value through, exactly what an approval emits.
     */
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? = inputs.first

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(
            id,
            buildJsonObject {
                put("action", "approvalGate")
                put("prompt", prompt)
            },
        )
        return inputs.first
    }
}
