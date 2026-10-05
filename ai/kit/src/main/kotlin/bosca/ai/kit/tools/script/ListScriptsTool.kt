package bosca.ai.kit.tools.script

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.scripting.model.ScriptType
import bosca.scripting.service.ScriptService
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable

/** Lists the available scripts, optionally filtered by [ScriptType]. */
class ListScriptsTool(
    private val scriptService: ScriptService,
) : KitTool<ListScriptsTool.Input, ListScriptsTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "list_scripts",
    description = "List all available scripts, optionally filtered by type (GENERAL, TRIGGER, TOOL, EPHEMERAL)",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("Optional script type filter: GENERAL, TRIGGER, TOOL, or EPHEMERAL. Leave empty to list all scripts.")
        val type: String = "",
    )

    @Serializable
    data class ScriptSummary(
        val id: String,
        val key: String,
        val name: String,
        val description: String,
        val type: String,
        val version: Int,
        val enabled: Boolean,
    )

    @Serializable
    data class Output(
        val scripts: List<ScriptSummary>,
        val count: Int,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val scripts = if (input.type.isNotBlank()) {
            val scriptType = try {
                ScriptType.valueOf(input.type.uppercase())
            } catch (_: IllegalArgumentException) {
                return Output(scripts = emptyList(), count = 0)
            }
            scriptService.getByType(scriptType)
        } else {
            scriptService.getAll()
        }
        val summaries = scripts.map { script ->
            ScriptSummary(
                id = script.id.toString(),
                key = script.key,
                name = script.name,
                description = script.description,
                type = script.type.name,
                version = script.version,
                enabled = script.enabled,
            )
        }
        return Output(scripts = summaries, count = summaries.size)
    }
}
