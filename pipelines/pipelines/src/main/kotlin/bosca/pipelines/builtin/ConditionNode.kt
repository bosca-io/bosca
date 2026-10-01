package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import com.dashjoin.jsonata.Jsonata
import com.dashjoin.jsonata.json.Json as JsonataJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Routes the inbound value to one of two labeled branches: [expression] (JSONata, evaluated against
 * the value's JSON form) is truthy → the `"true"` output port, else `"false"`. Downstream edges
 * connect to a specific port (`sourcePort`); the executor only propagates along the taken branch and
 * skips the other subtree. The value itself passes through unchanged.
 *
 * Conditions can reference `$eventCreated` — when the run's triggering occurrence happened
 * ([PipelineContext.inputCreated], ISO-8601; `$toMillis($eventCreated)` for arithmetic).
 */
@PipelineNodeType(
    category = NodeCategory.ROUTE,
    label = "Condition",
    description = "Evaluates a JSONata expression against the inbound value and routes it to the true or false branch.",
    group = "Core",
    subgroup = "Flow",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Value",
            description = "The value the condition's JSONata expression is evaluated against; it passes through unchanged on the taken branch.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "expression", control = SettingControl.CONDITION, label = "Condition", required = true,
            description = "Fields come from the inbound value — run a Dry Run to see them. Wire downstream nodes from the true or false handle; the untaken branch is skipped.",
        ),
    ],
)
@Serializable
@SerialName("condition")
class ConditionNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val expression: String,
    override val position: NodePosition = NodePosition(),
) : TransformNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        check(expression.isNotBlank()) { "Condition node '${name.ifBlank { id }}' has no condition — add at least one comparison" }
        val input = inputs.first ?: error("Condition node '$id' requires an input")
        val javaInput = JsonataJson.parseJson(input.encode(context.json).toString())
        val result = try {
            val jsonata = Jsonata.jsonata(expression)
            val frame = jsonata.createFrame()
            frame.bind("eventCreated", context.inputCreated.toString())
            jsonata.evaluate(javaInput, frame)
        }
        catch (e: Exception) {
            error("Condition node '${name.ifBlank { id }}' has an invalid expression ($expression): ${e.message}")
        }
        val taken = if (Jsonata.boolize(result)) "true" else "false"
        context.trace?.recordAction(id, buildJsonObject { put("takenBranch", taken) })
        return input.onPort(taken)
    }
}
