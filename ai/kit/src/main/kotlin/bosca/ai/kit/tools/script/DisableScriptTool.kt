package bosca.ai.kit.tools.script

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.scripting.service.ScriptService
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable

/** Disables a script to prevent it from being executed. */
class DisableScriptTool(
    private val scriptService: ScriptService,
) : KitTool<DisableScriptTool.Input, DisableScriptTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "disable_script",
    description = "Disable a script to prevent it from being executed",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The unique key of the script to disable")
        val key: String,
    )

    @Serializable
    data class Output(
        val key: String,
        val enabled: Boolean,
        val success: Boolean,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val script = scriptService.getByKey(input.key)
            ?: return Output(key = input.key, enabled = false, success = false, error = "Script not found with key: ${input.key}")
        scriptService.disable(script.id)
        return Output(key = input.key, enabled = false, success = true)
    }
}
