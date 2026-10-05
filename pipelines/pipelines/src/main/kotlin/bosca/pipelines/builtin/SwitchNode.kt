package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingField
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

/** One labeled branch of a [SwitchNode]: the value routes to [label] when [expression] is truthy. */
@Serializable
data class SwitchCase(
    /** The output port this case routes to — wire a downstream edge from a handle of this name. */
    val label: String,
    /** JSONata predicate evaluated against the inbound value; the first truthy case wins. */
    val expression: String,
)

/**
 * Multi-way routing: generalizes [ConditionNode]'s true/false to **N labeled cases plus a default**.
 * Each case's [SwitchCase.expression] (JSONata, evaluated against the inbound value's JSON form) is
 * tried **in order**; the value is emitted on the first truthy case's port (its [SwitchCase.label]),
 * or on [defaultLabel] if none match — so exactly one branch is taken. The value passes through
 * unchanged.
 *
 * Like Condition, routing is by output port: downstream edges set `sourcePort` to a case label (or
 * the default), and the executor only propagates along the taken branch, skipping the rest. The
 * case-named handles are dynamic per node instance, so the editor derives them from [cases] +
 * [defaultLabel] rather than from a static descriptor. Expressions may
 * reference `$eventCreated` (the run's occurrence time, ISO-8601).
 */
@PipelineNodeType(
    category = NodeCategory.ROUTE,
    label = "Switch",
    description = "Evaluates labeled cases in order and routes the inbound value to the first match's branch, or to the default.",
    group = "Core",
    subgroup = "Flow",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Value",
            description = "The value each case's JSONata expression is evaluated against; it passes through unchanged on the matched branch.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "cases", control = SettingControl.GROUP_LIST, label = "Cases", itemLabel = "Case",
            description = "Cases are evaluated top to bottom; the value routes to the first whose expression is truthy, otherwise to the default. Wire a downstream node from each case's handle.",
            fields = [
                SettingField(name = "label", control = SettingControl.TEXT, label = "Branch label", required = true, mono = true, placeholder = "e.g. vip"),
                SettingField(name = "expression", control = SettingControl.TEXT, label = "When (JSONata)", required = true, mono = true, placeholder = "e.g. tier = 'gold'"),
            ],
        ),
        SettingSlot(
            name = "defaultLabel", control = SettingControl.TEXT, label = "Default branch label", mono = true, default = "default",
        ),
    ],
)
@Serializable
@SerialName("switch")
class SwitchNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val cases: List<SwitchCase> = emptyList(),
    /** Port taken when no case matches. */
    val defaultLabel: String = "default",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        check(cases.isNotEmpty()) { "Switch node '${name.ifBlank { id }}' has no cases — add at least one" }
        val input = inputs.first ?: error("Switch node '${name.ifBlank { id }}' requires an input")
        val javaInput = JsonataJson.parseJson(input.encode(context.json).toString())

        val matched = cases.firstOrNull { case ->
            check(case.expression.isNotBlank()) {
                "Switch node '${name.ifBlank { id }}' case '${case.label}' has no expression"
            }
            val result = try {
                val jsonata = Jsonata.jsonata(case.expression)
                val frame = jsonata.createFrame()
                frame.bind("eventCreated", context.inputCreated.toString())
                jsonata.evaluate(javaInput, frame)
            } catch (e: Exception) {
                error("Switch node '${name.ifBlank { id }}' case '${case.label}' has an invalid expression (${case.expression}): ${e.message}")
            }
            Jsonata.boolize(result)
        }

        val port = matched?.label ?: defaultLabel
        context.trace?.recordAction(id, buildJsonObject { put("takenCase", port) })
        return input.onPort(port)
    }
}
