package bosca.pipelines.builtin

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.serialization.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action: runs a stored script ([scriptId]) in-process via core-scripting and returns its JSON
 * result as this node's output — synchronous, unlike `ExecuteJob`. The inbound value is handed to the
 * script as its `input`. Services resolved natively via `provide`; runs under the run's principal.
 * Reaches scripting only through its `core-scripting` contracts (no impl→impl).
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Execute Script",
    description = "Runs a stored script with the inbound value as its input and outputs the script's JSON result.",
    group = "Core",
    subgroup = "Actions",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Input",
            description = "The value passed to the script as its input; the script's JSON result becomes the output.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "scriptId", control = SettingControl.REFERENCE, reference = ReferenceSource.SCRIPT,
            label = "Script", required = true, placeholder = "Pick a script…",
            description = "Runs synchronously; the script result flows to downstream nodes.",
        ),
        SettingSlot(
            name = "dryRunEnabled", control = SettingControl.BOOLEAN, label = "Run during dry runs", default = "false",
            description = "Enable only for scripts that are pure transforms (no side effects).",
        ),
    ],
)
@Serializable
@SerialName("executeScript")
class ExecuteScriptNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val scriptId: UUID,
    /**
     * Run the script during dry runs too. For scripts that are pure transforms (no side effects)
     * this lets the dry-run trace carry their real output downstream; leave off for scripts that
     * mutate anything.
     */
    val dryRunEnabled: Boolean = false,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    /**
     * Running a script IS the side effect, so a dry run skips a side-effecting script (records the
     * would-be invocation, no output downstream) — unless [dryRunEnabled] marks it a pure transform,
     * in which case the dry run runs the script for real so its output flows on.
     */
    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        if (dryRunEnabled) return execute(context, inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "executeScript")
            put("scriptId", scriptId.toString())
            put("input", inputs.first?.encode(context.json) ?: JsonNull)
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val script = provide<ScriptService>().get(scriptId)
            ?: error("ExecuteScript node '$id': script not found: $scriptId")
        val scriptContext = DefaultScriptContext(
            context.authentication,
            CoroutineScope(currentCoroutineContext()),
            inputs.first?.encode(context.json) ?: JsonNull,
            context.json,
        )
        val result = provide<ScriptExecutionService>().executeAsJson(script, scriptContext)
        return PipelineValue.ofJson(result)
    }
}
