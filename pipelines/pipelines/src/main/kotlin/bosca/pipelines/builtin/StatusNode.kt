package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull

/**
 * A named progress marker: authors place Status nodes at the milestones a run's
 * OBSERVERS care about ("Tagged", "Built", "Deployed to development", …), and step-oriented UIs (the
 * release dashboard) show ONE row per Status node — resolved up front, updating live — instead of the
 * raw engine node chain. At run time it is a pure pass-through: the inbound value flows out unchanged,
 * and the run record marks the step reached.
 */
@PipelineNodeType(
    // ROUTE, like the Approval Gate: the validator treats ROUTE nodes as type-transparent, so a
    // Status marker can sit on any wire without breaking the connection's static typing.
    category = NodeCategory.ROUTE,
    label = "Status",
    description = "A named progress marker: step-oriented UIs show one row per Status node. Passes its input through unchanged.",
    group = "Core",
    subgroup = "Flow",
    inputs = [
        InputSlot(
            name = "in", kind = SlotKind.ANY, typeLabel = "Value",
            description = "Any value — it flows through unchanged.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out", kind = SlotKind.ANY, typeLabel = "Value",
            description = "The inbound value, unchanged.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "title", control = SettingControl.TEXT, label = "Title", required = true,
            placeholder = "e.g. Deployed to development",
            description = "What this milestone is called in step-oriented views.",
        ),
    ],
)
@Serializable
@SerialName("status")
class StatusNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The milestone's display title in step-oriented views. */
    val title: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue =
        inputs.first ?: PipelineValue.ofJson(JsonNull)

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue =
        execute(context, inputs)
}
