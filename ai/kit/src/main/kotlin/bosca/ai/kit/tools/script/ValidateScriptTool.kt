package bosca.ai.kit.tools.script

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.scripting.engine.Engine
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Validates script source for security violations (and optionally compiles it) without saving. */
class ValidateScriptTool(
    private val engine: Engine,
) : KitTool<ValidateScriptTool.Input, ValidateScriptTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "validate_script",
    description = "Validate script source code for security violations without saving. Optionally compile the script to check for syntax and type errors.",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The Kotlin script source code to validate")
        val source: String,
        @property:LLMDescription("Set to true to also compile the script and check for syntax/type errors. Compilation is slower but catches more issues. Default is false (security validation only).")
        val compile: Boolean = false,
    )

    @Serializable
    data class Output(
        val valid: Boolean,
        val compiled: Boolean,
        val errors: List<String>,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        // Always run security validation first.
        try {
            engine.validate(input.source)
        } catch (e: SecurityException) {
            return Output(valid = false, compiled = false, errors = listOf(e.message ?: "Security violation detected"))
        }

        if (!input.compile) {
            return Output(valid = true, compiled = false, errors = emptyList())
        }

        // Compile with a temp key to avoid polluting the engine cache.
        val tempKey = "__validate_${Uuid.random()}"
        return try {
            engine.compile<Any>(tempKey, 0, input.source)
            engine.invalidate(tempKey, 0)
            Output(valid = true, compiled = true, errors = emptyList())
        } catch (e: Exception) {
            engine.invalidate(tempKey, 0)
            Output(valid = false, compiled = true, errors = listOf(e.message ?: "Compilation failed"))
        }
    }
}
