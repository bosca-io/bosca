package bosca.cli.mcp

import bosca.cli.api.ScriptingApi
import bosca.graphql.gen.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** Exposes script operations directly through the scoped Bosca API. */
object ScriptingToolRegistrar {
    val tool = Tool(
        name = "bosca_script",
        description = "Discover, read, validate, create, edit, enable, disable, delete and execute Bosca Kotlin scripts. " +
            "Use get before edit and pass its expectedVersion. script is an object of definition fields; omitted edit fields are preserved and explicit null clears optional JSON fields. " +
            "dryRun previews create/edit without saving. validate checks source without saving or executing; compile=true also checks syntax and types using the worker. " +
            "Pass the id or key of the script being edited to validate; validating without one requires permission to create scripts. " +
            "TOOL creation registers an agent tool and assigns it to kit.script when present. EPHEMERAL scripts are soft-deleted. " +
            "execute can have side effects: use only when requested and never automatically retry an uncertain execution. " +
            "Scopes restrict access in addition to script group permissions; a preview is not an execution approval.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("action") { put("type", "string"); put("enum", JsonArray(listOf("list", "get", "validate", "create", "edit", "enable", "disable", "delete", "execute").map(::JsonPrimitive))) }
            for (key in listOf("id", "key", "source", "type")) putJsonObject(key) { put("type", "string") }
            putJsonObject("script") { put("type", "object"); put("description", "Definition: key, name, source, type (default GENERAL), description, public, inputSchema, outputSchema, configuration") }
            putJsonObject("input") { put("description", "Execution input, any JSON value") }
            putJsonObject("expectedVersion") { put("type", "integer"); put("minimum", 1) }
            for (key in listOf("compile", "dryRun", "includeDeleted")) putJsonObject(key) { put("type", "boolean") }
            putJsonObject("offset") { put("type", "integer"); put("minimum", 0) }
            putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 100) }
        }, required = listOf("action")),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true),
    )

    fun registerAll(server: Server, api: ScriptingApi) {
        server.addTool(tool) { request -> handle(api, request.arguments ?: error("Missing arguments")) }
    }

    internal suspend fun handle(api: ScriptingApi, args: Map<String, JsonElement>): CallToolResult = try {
        val action = args.required("action")
        val output = when (action) {
            "list" -> {
                val offset = args["offset"]?.jsonPrimitive?.int ?: 0
                val limit = args["limit"]?.jsonPrimitive?.int ?: 20
                require(offset >= 0 && limit in 1..100) { "Use a nonnegative offset and a limit between 1 and 100" }
                Json.encodeToString(ListManagedScriptsData.serializer(), api.list(args.string("type")?.let(ScriptType::valueOf), args.bool("includeDeleted"), offset, limit))
            }
            "validate" -> {
                val target = if (args.string("id") != null || args.string("key") != null) find(api, args).id else null
                Json.encodeToString(ValidateManagedScriptData.serializer(), api.validate(args.required("source"), args.bool("compile"), target))
            }
            "create", "edit" -> {
                val patch = args["script"]?.jsonObject ?: error("Missing script definition")
                val previous = if (action == "edit") find(api, args) else null
                val expectedVersion = if (previous != null) args["expectedVersion"]?.jsonPrimitive?.int ?: error("Missing expectedVersion; read the script before editing") else null
                require(previous == null || expectedVersion == previous.version) { "Script changed; read its current version before editing" }
                val fields = buildJsonObject {
                    if (previous != null) previous.inputFields().forEach { (key, value) -> put(key, value) }
                    else { put("type", "GENERAL"); put("description", ""); put("public", false) }
                    patch.forEach { (key, value) -> put(key, value) }
                }
                val definition = Json.decodeFromJsonElement(ScriptInput.serializer(), fields)
                require(definition.key.isNotBlank() && definition.name.isNotBlank() && definition.source.isNotBlank()) { "key, name and source must not be blank" }
                if (previous != null) {
                    require(!(previous.type == ScriptType.EPHEMERAL && definition.type == ScriptType.TOOL) &&
                        !(previous.type != ScriptType.EPHEMERAL && definition.type == ScriptType.EPHEMERAL)) { "Create a new script for this type transition" }
                }
                if (args.bool("dryRun")) {
                    val validation = api.validate(definition.source, args.bool("compile"), previous?.id).scripts.validateScript
                    buildJsonObject {
                        put("dryRun", true); put("valid", validation.valid); put("compiled", validation.compiled)
                        put("errors", JsonArray(validation.errors.map(::JsonPrimitive))); put("script", fields)
                        put("changedFields", JsonArray(fields.keys.filter { previous?.inputFields()?.get(it) != fields[it] }.map(::JsonPrimitive)))
                    }.toString()
                } else if (previous == null) {
                    Json.encodeToString(AddManagedScriptData.serializer(), api.add(definition))
                } else {
                    Json.encodeToString(EditManagedScriptData.serializer(), api.edit(previous.id, definition, requireNotNull(expectedVersion)))
                }
            }
            "get", "enable", "disable", "delete", "execute" -> {
                val script = find(api, args)
                when (action) {
                    "get" -> script.inputFields().toMutableMap().apply {
                        put("id", JsonPrimitive(script.id.toString())); put("version", JsonPrimitive(script.version)); put("enabled", JsonPrimitive(script.enabled))
                    }.let(::JsonObject).toString()
                    "enable" -> Json.encodeToString(EnableManagedScriptData.serializer(), api.enable(script.id))
                    "disable" -> Json.encodeToString(DisableManagedScriptData.serializer(), api.disable(script.id))
                    "delete" -> Json.encodeToString(DeleteManagedScriptData.serializer(), api.delete(script.id))
                    else -> {
                        check(script.enabled) { "Script is disabled" }
                        Json.encodeToString(ExecuteManagedScriptData.serializer(), api.execute(script.id, args["input"]))
                    }
                }
            }
            else -> error("Unknown script action")
        }
        CallToolResult(content = listOf(TextContent(output)))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { CallToolResult(content = listOf(TextContent("Error: ${failure.message}")), isError = true) }

    private suspend fun find(api: ScriptingApi, args: Map<String, JsonElement>): IManagedScript {
        require((args.string("id") != null) != (args.string("key") != null)) { "Supply exactly one script id or key" }
        return requireNotNull(args.string("id")?.let { api.get(Uuid.parse(it)) } ?: args.string("key")?.let { api.get(it) }) { "Script not found or unavailable" }
    }

    private fun IManagedScript.inputFields() = buildJsonObject {
        put("key", key); put("name", name); put("description", description); put("type", type.name); put("source", source); put("public", public)
        put("inputSchema", inputSchema ?: JsonNull); put("outputSchema", outputSchema ?: JsonNull); put("configuration", configuration ?: JsonNull)
    }
    private fun Map<String, JsonElement>.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun Map<String, JsonElement>.required(key: String) = requireNotNull(string(key)?.takeIf(String::isNotBlank)) { "Missing $key" }
    private fun Map<String, JsonElement>.bool(key: String) = this[key]?.jsonPrimitive?.boolean ?: false
}
