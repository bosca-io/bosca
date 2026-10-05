package bosca.ai.kit.tools.script

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.agents.model.AgentToolInput
import bosca.ai.agents.service.AgentService
import bosca.ai.agents.service.AgentToolService
import bosca.ai.kit.tools.KitTool
import bosca.scripting.engine.Engine
import bosca.scripting.model.ScriptInput
import bosca.scripting.model.ScriptType
import bosca.scripting.service.ScriptService
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Edits an existing script by key, merging only the provided fields over its current values. */
class EditScriptTool(
    private val scriptService: ScriptService,
    private val json: Json,
    private val agentToolService: AgentToolService,
    private val agentService: AgentService,
    private val engine: Engine,
) : KitTool<EditScriptTool.Input, EditScriptTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "edit_script",
    description = "Edit an existing script by key. Only provide fields you want to change — unspecified fields keep their current values.",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The unique key of the script to edit")
        val key: String,
        @property:LLMDescription("New display name (leave empty to keep current)")
        val name: String = "",
        @property:LLMDescription("New description (leave empty to keep current)")
        val description: String = "",
        @property:LLMDescription("New script type: GENERAL, TRIGGER, TOOL, or EPHEMERAL (leave empty to keep current)")
        val type: String = "",
        @property:LLMDescription("New Kotlin script source code (leave empty to keep current)")
        val source: String = "",
        @property:LLMDescription("New JSON Schema string for input parameters (leave empty to keep current)")
        val inputSchema: String = "",
        @property:LLMDescription("New JSON Schema string for output format (leave empty to keep current)")
        val outputSchema: String = "",
        @property:LLMDescription("New JSON configuration string (leave empty to keep current)")
        val configuration: String = "",
        @property:LLMDescription("Set to true to validate and preview changes without saving. Shows what fields would change and what usages could be affected.")
        val dryRun: Boolean = false,
    )

    @Serializable
    data class Output(
        val id: String,
        val key: String,
        val name: String,
        val type: String,
        val version: Int,
        val enabled: Boolean,
        val success: Boolean,
        val dryRun: Boolean = false,
        val changedFields: List<String> = emptyList(),
        val agentToolCount: Int = 0,
        val error: String? = null,
        val note: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val existing = scriptService.getByKey(input.key)
            ?: return errorOutput(input.key, "Script not found with key: ${input.key}")

        val mergedType = if (input.type.isNotBlank()) {
            try {
                ScriptType.valueOf(input.type.uppercase())
            } catch (_: IllegalArgumentException) {
                return errorOutput(input.key, "Invalid script type '${input.type}'. Must be GENERAL, TRIGGER, TOOL, or EPHEMERAL")
            }
        } else {
            existing.type
        }

        // Prevent EPHEMERAL <-> TOOL type transitions.
        if (mergedType == ScriptType.TOOL && existing.type == ScriptType.EPHEMERAL) {
            return errorOutput(input.key, "Cannot change an EPHEMERAL script to TOOL. Create a new TOOL script instead.")
        }
        if (mergedType == ScriptType.EPHEMERAL && existing.type != ScriptType.EPHEMERAL) {
            return errorOutput(input.key, "Cannot change a ${existing.type.name} script to EPHEMERAL. Create a new EPHEMERAL script instead.")
        }

        val mergedSource = input.source.ifBlank { existing.source }
        val mergedName = input.name.ifBlank { existing.name }
        val mergedDescription = input.description.ifBlank { existing.description }

        val mergedInputSchema = if (input.inputSchema.isNotBlank()) {
            try {
                json.parseToJsonElement(input.inputSchema)
            } catch (_: Exception) {
                return errorOutput(input.key, "Invalid JSON in inputSchema")
            }
        } else existing.inputSchema

        val mergedOutputSchema = if (input.outputSchema.isNotBlank()) {
            try {
                json.parseToJsonElement(input.outputSchema)
            } catch (_: Exception) {
                return errorOutput(input.key, "Invalid JSON in outputSchema")
            }
        } else existing.outputSchema

        val mergedConfiguration = if (input.configuration.isNotBlank()) {
            try {
                json.parseToJsonElement(input.configuration)
            } catch (_: Exception) {
                return errorOutput(input.key, "Invalid JSON in configuration")
            }
        } else existing.configuration

        try {
            engine.validate(mergedSource)
        } catch (e: SecurityException) {
            return errorOutput(input.key, "Source validation failed: ${e.message}")
        }

        if (input.dryRun) {
            val changedFields = mutableListOf<String>()
            if (input.name.isNotBlank() && input.name != existing.name) changedFields.add("name")
            if (input.description.isNotBlank() && input.description != existing.description) changedFields.add("description")
            if (input.type.isNotBlank() && mergedType != existing.type) changedFields.add("type")
            if (input.source.isNotBlank() && input.source != existing.source) changedFields.add("source")
            if (input.inputSchema.isNotBlank()) changedFields.add("inputSchema")
            if (input.outputSchema.isNotBlank()) changedFields.add("outputSchema")
            if (input.configuration.isNotBlank()) changedFields.add("configuration")

            val agentTools = agentToolService.getByScriptId(existing.id)
            val note = if (mergedType == ScriptType.TOOL && existing.type != ScriptType.TOOL && agentTools.isEmpty()) {
                "Will auto-register as agent tool and assign to the Script Agent."
            } else null

            return Output(
                id = existing.id.toString(),
                key = existing.key,
                name = mergedName,
                type = mergedType.name,
                version = existing.version,
                enabled = existing.enabled,
                success = true,
                dryRun = true,
                changedFields = changedFields,
                agentToolCount = agentTools.size,
                note = note,
            )
        }

        val scriptInput = ScriptInput(
            key = existing.key,
            name = mergedName,
            description = mergedDescription,
            type = mergedType,
            source = mergedSource,
            inputSchema = mergedInputSchema,
            outputSchema = mergedOutputSchema,
            configuration = mergedConfiguration,
        )

        return try {
            val updated = scriptService.edit(existing.id, scriptInput)
            var note: String? = null
            if (mergedType == ScriptType.TOOL && existing.type != ScriptType.TOOL && agentToolService.getByScriptId(updated.id).isEmpty()) {
                note = registerAsAgentTool(updated.key, updated.name, updated.description, updated.id)
            }
            Output(
                id = updated.id.toString(),
                key = updated.key,
                name = updated.name,
                type = updated.type.name,
                version = updated.version,
                enabled = updated.enabled,
                success = true,
                note = note,
            )
        } catch (e: IllegalStateException) {
            errorOutput(input.key, "Script was modified concurrently. Please re-fetch and try again.")
        } catch (e: Exception) {
            errorOutput(input.key, "Failed to edit script: ${e.message}")
        }
    }

    /** Register a newly-TOOL script as an agent tool and assign it to the script agent; degrades gracefully. */
    private suspend fun registerAsAgentTool(key: String, name: String, description: String, scriptId: kotlin.uuid.Uuid): String = try {
        val agentTool = agentToolService.add(AgentToolInput(key = key, name = name, description = description, scriptId = scriptId))
        val scriptAgent = agentService.getByKey("kit.script")
        if (scriptAgent != null) {
            agentService.addTool(scriptAgent.id, agentTool.id)
            "Auto-registered as agent tool '$key' and assigned to the Script Agent."
        } else {
            "Auto-registered as agent tool '$key' but the Script Agent was not found — assign it manually."
        }
    } catch (e: Exception) {
        "Type changed to TOOL but auto-registration failed: ${e.message}. Register it manually."
    }

    private fun errorOutput(key: String, error: String) = Output(
        id = "", key = key, name = "", type = "", version = 0, enabled = false, success = false, error = error,
    )
}
