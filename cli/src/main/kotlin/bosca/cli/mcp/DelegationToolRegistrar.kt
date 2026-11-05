package bosca.cli.mcp

import bosca.cli.api.DelegationApi
import bosca.graphql.client.GraphQLJson
import bosca.graphql.gen.IKitDelegationFragment
import bosca.graphql.gen.KitDelegationInput
import bosca.graphql.gen.KitDelegationResultInput
import bosca.graphql.gen.KitDelegationSettingsData
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

/** One typed delegation surface, available only inside a bound harness conversation. */
object DelegationToolRegistrar {
    internal val tool = Tool(
        name = "bosca_delegate",
        description = """
            Delegate a bounded task to a managed child harness in this Bosca conversation.
            settings returns approved profiles and depth, concurrency and runtime budgets. start takes
            input: requestId (stable UUID), brief, expectedResult, profile, optional context/artifacts,
            scopes, runtimeSeconds and deadline. Reuse the same requestId and exact input on retry;
            never create another child to recover an uncertain start. Select only needed Bosca source
            references; the child has its own workspace and does not receive the parent's full history.
            context entries have kind, id, optional ref/path; kinds are METADATA, COLLECTION, TASK,
            SPEC, REQUIREMENT, GIT_FILE. Artifacts are Bosca metadata UUIDs. Scopes can only narrow
            authority. You cannot choose infrastructure or bypass the managed budgets.
            list (offset/limit) and get (id) recover durable tasks. wait (id) yields the Runner while the
            child executes and returns within about 20 seconds; QUEUED/RUNNING remain unfinished.
            Wait again on the same id as needed. A child uses submit with output {summary, artifacts:
            [{metadataId,label}]} before returning; its terminal native receipt confirms completion.
            Partial output on failed, cancelled, timed-out or interrupted tasks is not proof of success.
            cancel requests cancellation of a child and its descendants; completed actions are not undone.
        """.trimIndent(),
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("action") { put("type", "string"); put("enum", JsonArray(listOf("settings", "start", "list", "get", "wait", "cancel", "submit").map(::JsonPrimitive))) }
            putJsonObject("id") { put("type", "string"); put("format", "uuid") }
            putJsonObject("input") {
                put("type", "object")
                putJsonObject("properties") {
                    for (key in listOf("requestId", "brief", "expectedResult", "profile", "deadline")) putJsonObject(key) { put("type", "string") }
                    putJsonObject("runtimeSeconds") { put("type", "integer"); put("minimum", 1) }
                    for (key in listOf("artifacts", "scopes")) putJsonObject(key) { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                    putJsonObject("context") {
                        put("type", "array"); putJsonObject("items") {
                            put("type", "object"); putJsonObject("properties") {
                                for (key in listOf("kind", "id", "ref", "path")) putJsonObject(key) { put("type", "string") }
                            }; put("required", JsonArray(listOf("kind", "id").map(::JsonPrimitive)))
                        }
                    }
                }
                put("required", JsonArray(listOf("requestId", "brief", "expectedResult", "profile").map(::JsonPrimitive)))
            }
            putJsonObject("output") {
                put("type", "object"); putJsonObject("properties") {
                    putJsonObject("summary") { put("type", "string") }
                    putJsonObject("artifacts") { put("type", "array"); putJsonObject("items") {
                        put("type", "object"); putJsonObject("properties") {
                            for (key in listOf("metadataId", "label")) putJsonObject(key) { put("type", "string") }
                        }; put("required", JsonArray(listOf(JsonPrimitive("metadataId"))))
                    } }
                }; put("required", JsonArray(listOf(JsonPrimitive("summary"))))
            }
            putJsonObject("offset") { put("type", "integer"); put("minimum", 0) }
            putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 100) }
        }, required = listOf("action")),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true),
    )

    fun registerAll(server: Server, api: DelegationApi) { server.addTool(tool) { handle(api, it.arguments ?: error("Missing arguments")) } }

    internal suspend fun handle(api: DelegationApi, args: Map<String, JsonElement>): CallToolResult = try {
        fun id() = Uuid.parse(requireNotNull(args["id"]?.jsonPrimitive?.content) { "Missing id" })
        val result = when (args["action"]?.jsonPrimitive?.content) {
            "settings" -> GraphQLJson.encodeToJsonElement(KitDelegationSettingsData.Ai.Delegations.Settings.serializer(), api.settings())
            "start" -> api.start(GraphQLJson.decodeFromJsonElement(KitDelegationInput.serializer(), requireNotNull(args["input"]) { "Missing input" })).json()
            "get" -> api.get(id())?.json() ?: JsonNull
            "list" -> {
                val offset = args["offset"]?.jsonPrimitive?.long ?: 0
                val limit = args["limit"]?.jsonPrimitive?.int ?: 20
                require(offset >= 0 && limit in 1..100) { "Invalid child task page" }
                JsonArray(api.list(offset, limit).map { it.json() })
            }
            "wait" -> api.wait(id()).json()
            "cancel" -> api.cancel(id()).json()
            "submit" -> api.submit(GraphQLJson.decodeFromJsonElement(KitDelegationResultInput.serializer(), requireNotNull(args["output"]) { "Missing output" })).json()
            else -> error("Unknown delegation action")
        }
        CallToolResult(content = listOf(TextContent(result.toString())))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { CallToolResult(content = listOf(TextContent("Error: ${failure.message}")), isError = true) }

    private fun IKitDelegationFragment.json() = buildJsonObject {
        put("id", id.toString()); put("parentSessionId", parentSessionId.toString()); put("parentTurnId", parentTurnId.toString())
        put("childSessionId", childSessionId.toString()); put("profile", profile); put("depth", depth)
        put("runtimeSeconds", runtimeSeconds); put("deadline", deadline.toString()); put("status", status.name); put("partial", partial); put("failure", failure)
        put("result", result?.let { output -> buildJsonObject {
            put("summary", output.summary); put("artifacts", JsonArray(output.artifacts.map { artifact -> buildJsonObject {
                put("metadataId", artifact.metadataId.toString()); put("label", artifact.label)
            } }))
        } } ?: JsonNull)
    }
}
