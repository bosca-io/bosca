package bosca.ai.kit.tools.script

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.agents.service.AgentToolService
import bosca.ai.kit.tools.KitTool
import bosca.scripting.service.ScriptService
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable

/** Returns a script's full details by key, including its source, schemas, and current usages. */
class GetScriptTool(
    private val scriptService: ScriptService,
    private val agentToolService: AgentToolService,
) : KitTool<GetScriptTool.Input, GetScriptTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "get_script",
    description = "Get the details of a script by its key, including its source code, input/output schemas, configuration, and current usages (agent tools)",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The unique key of the script to retrieve")
        val key: String,
    )

    @Serializable
    data class ToolInfo(val id: String, val key: String, val name: String)

    @Serializable
    data class Output(
        val id: String,
        val key: String,
        val name: String,
        val description: String,
        val type: String,
        val source: String,
        val version: Int,
        val enabled: Boolean,
        val inputSchema: String?,
        val outputSchema: String?,
        val configuration: String?,
        val agentTools: List<ToolInfo> = emptyList(),
        val found: Boolean,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val script = scriptService.getByKey(input.key)
            ?: return Output(
                id = "", key = input.key, name = "", description = "", type = "", source = "",
                version = 0, enabled = false, inputSchema = null, outputSchema = null,
                configuration = null, found = false, error = "Script not found with key: ${input.key}",
            )

        val tools = agentToolService.getByScriptId(script.id).map { tool ->
            ToolInfo(id = tool.id.toString(), key = tool.key, name = tool.name)
        }

        return Output(
            id = script.id.toString(),
            key = script.key,
            name = script.name,
            description = script.description,
            type = script.type.name,
            source = script.source,
            version = script.version,
            enabled = script.enabled,
            inputSchema = script.inputSchema?.toString(),
            outputSchema = script.outputSchema?.toString(),
            configuration = script.configuration?.toString(),
            agentTools = tools,
            found = true,
        )
    }
}
