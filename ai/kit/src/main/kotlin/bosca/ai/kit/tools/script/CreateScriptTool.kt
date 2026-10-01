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
import kotlinx.serialization.json.JsonElement

/** Creates a new server-side Kotlin script, validating its source before saving. */
class CreateScriptTool(
    private val scriptService: ScriptService,
    private val json: Json,
    private val agentToolService: AgentToolService,
    private val agentService: AgentService,
    private val engine: Engine,
) : KitTool<CreateScriptTool.Input, CreateScriptTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "create_script",
    description = "Create a new server-side Kotlin script. Validates source code for security violations before saving.",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("Unique identifier for the script (e.g., 'my-data-processor')")
        val key: String,
        @property:LLMDescription("Human-readable display name for the script")
        val name: String,
        @property:LLMDescription("Description of what the script does")
        val description: String = "",
        @property:LLMDescription("Script type: GENERAL (standalone), TRIGGER (event-driven), TOOL (agent tool), or EPHEMERAL (transient one-off, soft-deleted when done)")
        val type: String = "GENERAL",
        @property:LLMDescription("The Kotlin script source code. Must use the main {} block pattern.")
        val source: String,
        @property:LLMDescription("Optional JSON Schema string defining the script's input parameters")
        val inputSchema: String = "",
        @property:LLMDescription("Optional JSON Schema string defining the script's output format")
        val outputSchema: String = "",
        @property:LLMDescription("Optional JSON configuration string for the script")
        val configuration: String = "",
        @property:LLMDescription("Set to true to validate and preview what would be created without actually saving. Use this for confirmation before creating.")
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
        val error: String? = null,
        val note: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val scriptType = try {
            ScriptType.valueOf(input.type.uppercase())
        } catch (_: IllegalArgumentException) {
            return errorOutput(input.key, "Invalid script type '${input.type}'. Must be GENERAL, TRIGGER, TOOL, or EPHEMERAL")
        }

        if (scriptService.getByKey(input.key) != null) {
            return errorOutput(input.key, "A script with key '${input.key}' already exists")
        }

        try {
            engine.validate(input.source)
        } catch (e: SecurityException) {
            return errorOutput(input.key, "Source validation failed: ${e.message}")
        }

        val inputSchema = if (input.inputSchema.isNotBlank()) {
            parseJsonOrNull(input.inputSchema) ?: return errorOutput(input.key, "Invalid JSON in inputSchema")
        } else null
        val outputSchema = if (input.outputSchema.isNotBlank()) {
            parseJsonOrNull(input.outputSchema) ?: return errorOutput(input.key, "Invalid JSON in outputSchema")
        } else null
        val configuration = if (input.configuration.isNotBlank()) {
            parseJsonOrNull(input.configuration) ?: return errorOutput(input.key, "Invalid JSON in configuration")
        } else null

        if (input.dryRun) {
            val note = if (scriptType == ScriptType.TOOL) "Will auto-register as agent tool and assign to the Script Agent." else null
            return Output(
                id = "", key = input.key, name = input.name, type = scriptType.name,
                version = 1, enabled = true, success = true, dryRun = true, note = note,
            )
        }

        val scriptInput = ScriptInput(
            key = input.key,
            name = input.name,
            description = input.description,
            type = scriptType,
            source = input.source,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            configuration = configuration,
        )

        return try {
            val script = scriptService.add(scriptInput)
            val note = if (scriptType == ScriptType.TOOL) registerAsAgentTool(script.key, script.name, script.description, script.id) else null
            Output(
                id = script.id.toString(),
                key = script.key,
                name = script.name,
                type = script.type.name,
                version = script.version,
                enabled = script.enabled,
                success = true,
                note = note,
            )
        } catch (e: Exception) {
            errorOutput(input.key, "Failed to create script: ${e.message}")
        }
    }

    /** Register a TOOL script as an agent tool and assign it to the script agent; degrades gracefully. */
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
        "TOOL script created but auto-registration as agent tool failed: ${e.message}. Register it manually."
    }

    private fun parseJsonOrNull(value: String): JsonElement? = try {
        json.parseToJsonElement(value)
    } catch (_: Exception) {
        null
    }

    private fun errorOutput(key: String, error: String) = Output(
        id = "", key = key, name = "", type = "", version = 0, enabled = false, success = false, error = error,
    )
}
