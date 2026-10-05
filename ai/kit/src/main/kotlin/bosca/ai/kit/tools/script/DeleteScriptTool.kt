package bosca.ai.kit.tools.script

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.agents.service.AgentToolService
import bosca.ai.kit.tools.KitTool
import bosca.scripting.model.ScriptType
import bosca.scripting.service.ScriptService
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable

/** Deletes a script by key, reporting (or cascade-deleting, with force) its dependent usages. */
class DeleteScriptTool(
    private val scriptService: ScriptService,
    private val agentToolService: AgentToolService,
) : KitTool<DeleteScriptTool.Input, DeleteScriptTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "delete_script",
    description = "Delete a script by key. Reports dependent agent tools. Use force=true to delete even with dependencies (they will be cascade-deleted).",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The unique key of the script to delete")
        val key: String,
        @property:LLMDescription("Set to true to delete even if the script has agent tools referencing it. These dependencies will be cascade-deleted.")
        val force: Boolean = false,
    )

    @Serializable
    data class Output(
        val success: Boolean,
        val deleted: Boolean,
        val agentToolCount: Int,
        val error: String? = null,
        val note: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val script = scriptService.getByKey(input.key)
            ?: return Output(success = false, deleted = false, agentToolCount = 0, error = "Script not found with key: ${input.key}")

        val agentTools = agentToolService.getByScriptId(script.id)

        if (agentTools.isNotEmpty() && !input.force) {
            return Output(
                success = false,
                deleted = false,
                agentToolCount = agentTools.size,
                error = "Script '${input.key}' has ${agentTools.size} agent tool(s). Use force=true to delete (dependencies will be cascade-deleted).",
            )
        }

        return try {
            scriptService.delete(script.id)
            Output(
                success = true,
                deleted = true,
                agentToolCount = agentTools.size,
                note = if (script.type == ScriptType.EPHEMERAL) "Ephemeral script soft-deleted (hidden but retained for diagnostics)" else null,
            )
        } catch (e: Exception) {
            Output(success = false, deleted = false, agentToolCount = 0, error = "Failed to delete script: ${e.message}")
        }
    }
}
