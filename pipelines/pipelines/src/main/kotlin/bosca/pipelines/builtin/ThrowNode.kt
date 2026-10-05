package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Fails the pipeline run on purpose — reaching this node throws, so the run terminates as FAILED with
 * [message]. Wire it to a failure/error port (e.g. a Wait for Build node's failure branch) to blow the run
 * up after a handled failure. Any inbound value is appended to the message for context.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Throw Error",
    description = "Fails the pipeline run with an error message — e.g. off a Wait for Build failure port to abort the run.",
    group = "Core",
    subgroup = "Actions",
    inputs = [
        InputSlot(
            name = "in", kind = SlotKind.ANY, typeLabel = "Cause",
            description = "Optional — its value is appended to the error message for context.", required = false,
        ),
    ],
    settings = [
        SettingSlot(
            name = "message", control = SettingControl.TEXT, label = "Error message",
            default = "Pipeline failed", placeholder = "e.g. Build failed",
            description = "The message the run fails with. The inbound value, if any, is appended.",
        ),
    ],
)
@Serializable
@SerialName("throw")
class ThrowNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The message the run fails with; the inbound value (if any) is appended for context. */
    val message: String = "Pipeline failed",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val msg = message.ifBlank { "Pipeline failed" }
        val cause = inputs.first?.encode(context.json)?.takeIf { it !is JsonNull }
        // An error-port payload ({"error": "..."}) reads as its message, not as raw JSON.
        val text = ((cause as? kotlinx.serialization.json.JsonObject)
            ?.takeIf { it.size == 1 }?.get("error") as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content
            ?: cause?.toString()
        error(if (text != null) "$msg: $text" else msg)
    }

    /** A dry run traces the intent rather than aborting the trace, so the whole graph is still walked. */
    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(id, buildJsonObject { put("action", "throw"); put("message", message.ifBlank { "Pipeline failed" }) })
        return null
    }
}
