package bosca.ai.kit.tools.script

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.tools.KitTool
import bosca.scripting.context.DefaultScriptContext
import bosca.scripting.service.ScriptExecutionService
import bosca.scripting.service.ScriptService
import bosca.security.service.AuthenticationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Executes an enabled script by key with optional JSON input, returning its output as JSON. */
class ExecuteScriptTool(
    private val scriptService: ScriptService,
    private val scriptExecutionService: ScriptExecutionService,
    private val json: Json,
) : KitTool<ExecuteScriptTool.Input, ExecuteScriptTool.Output>(
    Input.serializer(),
    Output.serializer(),
    name = "execute_script",
    description = "Execute a script by its key with optional JSON input. The script must be enabled. Returns the script's output as a JSON string.",
) {

    @Serializable
    data class Input(
        @property:LLMDescription("The unique key of the script to execute")
        val key: String,
        @property:LLMDescription("Optional JSON input to pass to the script. Must match the script's input schema if one is defined.")
        val input: String = "",
    )

    @Serializable
    data class Output(
        val result: String,
        val success: Boolean,
        val error: String? = null,
    )

    override suspend fun execute(authentication: AuthenticationContext, input: Input): Output {
        val script = scriptService.getByKey(input.key)
            ?: return Output(result = "", success = false, error = "Script not found with key: ${input.key}")

        if (!script.enabled) {
            return Output(result = "", success = false, error = "Script '${input.key}' is disabled")
        }

        val inputElement: JsonElement = if (input.input.isNotBlank()) {
            try {
                json.parseToJsonElement(input.input)
            } catch (_: Exception) {
                return Output(result = "", success = false, error = "Invalid JSON in input")
            }
        } else {
            JsonNull
        }

        val schemaElement = script.inputSchema
        if (schemaElement is JsonObject) {
            val error = validateInputAgainstSchema(inputElement, schemaElement)
            if (error != null) {
                return Output(
                    result = "",
                    success = false,
                    error = "Input does not match script's input schema: $error. Expected schema: $schemaElement",
                )
            }
        }

        val parentJob = currentCoroutineContext()[Job]
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob(parentJob))
        val context = DefaultScriptContext(
            authentication = authentication,
            scope = scope,
            input = inputElement,
            json = json,
        )

        return try {
            val result = scriptExecutionService.executeAsJson(script, context)
            Output(result = result.toString(), success = true)
        } catch (e: Exception) {
            Output(result = "", success = false, error = "Script execution failed: ${e.message}")
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        fun validateInputAgainstSchema(input: JsonElement, schema: JsonObject): String? {
            val schemaType = (schema["type"] as? JsonPrimitive)?.content
            if (schemaType == "object") {
                if (input !is JsonObject) return "Expected a JSON object but got ${input::class.simpleName}"
                val required = (schema["required"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: emptyList()
                val missing = required.filter { it !in input }
                if (missing.isNotEmpty()) return "Missing required fields: ${missing.joinToString()}"
                val properties = schema["properties"] as? JsonObject
                if (properties != null) {
                    for ((key, value) in input) {
                        val propSchema = properties[key] as? JsonObject
                        if (propSchema != null) {
                            val error = validateFieldType(key, value, propSchema)
                            if (error != null) return error
                        }
                    }
                }
            }
            return null
        }

        fun validateFieldType(name: String, value: JsonElement, propSchema: JsonObject): String? {
            if (value is JsonNull) return null
            val expectedType = (propSchema["type"] as? JsonPrimitive)?.content ?: return null
            return when (expectedType) {
                "string" -> if (value !is JsonPrimitive || !value.isString) "Field '$name' should be a string" else null
                "integer" -> if (value !is JsonPrimitive || value.isString) "Field '$name' should be an integer" else null
                "number" -> if (value !is JsonPrimitive || value.isString) "Field '$name' should be a number" else null
                "boolean" -> if (value !is JsonPrimitive || value.isString) "Field '$name' should be a boolean" else null
                "array" -> if (value !is JsonArray) "Field '$name' should be an array" else null
                "object" -> if (value !is JsonObject) "Field '$name' should be an object" else null
                else -> null
            }
        }
    }
}
