package bosca.cli.mcp

import bosca.cli.api.MemoryApi
import bosca.graphql.gen.IMemoryFragment
import bosca.graphql.gen.MemoryQuery
import bosca.graphql.gen.MemoryScope
import bosca.graphql.gen.RememberMemoryInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import kotlin.uuid.Uuid

/** MCP client adapter for the typed, permission-checked memory API. */
object MemoryToolRegistrar {
    internal val tool = Tool(
        name = "bosca_memory",
        description = """
            Remember preferences, facts, or decisions across conversations and harnesses in Bosca.
            Actions: remember, get, search, update, forget. Save or change memory only when the user
            explicitly asks; never extract memories automatically or store credentials. An explicit
            remember request needs no additional confirmation. Default scope PERSONAL is private to
            the account's group; COLLECTION requires collectionId and follows that collection's ACL.
            Cite sourceMetadataId when derived from content. Reuse requestId (a UUID) and the exact
            input when retrying remember. Read before update/forget and pass the returned revision.
            Search selects personal memories and the specified collectionIds, ids and tags (max 20).
            sourceChanged means uncertain: fetch the live source before relying on it; refreshSource
            acknowledges it when explicitly correcting a memory. Show the saved statement, scope,
            source and expiry. Without expires, a memory lasts until forgotten, independently of
            conversation deletion. Forget removes it from future retrieval, not retained chat/history.
        """.trimIndent(),
        inputSchema = ToolSchema(properties = buildJsonObject {
            put("action", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("remember", "get", "search", "update", "forget").map(::JsonPrimitive))) })
            for (key in listOf("requestId", "id", "statement", "collectionId", "sourceMetadataId", "conversationId", "expires", "expectedRevision", "text")) {
                put(key, buildJsonObject { put("type", "string") })
            }
            put("scope", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("PERSONAL", "COLLECTION").map(::JsonPrimitive))) })
            for (key in listOf("collectionIds", "ids", "tags")) put(key, buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "string") } })
            for (key in listOf("personal", "refreshSource")) put(key, buildJsonObject { put("type", "boolean") })
            for (key in listOf("offset", "limit")) put(key, buildJsonObject { put("type", "integer") })
        }, required = listOf("action")),
        annotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false),
    )

    fun registerAll(server: Server, api: MemoryApi) {
        server.addTool(tool) { request -> handle(api, request.arguments ?: error("Missing arguments")) }
    }

    internal suspend fun handle(api: MemoryApi, args: Map<String, JsonElement>): CallToolResult = try {
        val result = when (args.str("action")) {
            "remember" -> api.remember(RememberMemoryInput(
                requestId = Uuid.parse(args.str("requestId")), statement = args.str("statement"),
                scope = args.optStr("scope")?.let(MemoryScope::valueOf) ?: MemoryScope.PERSONAL,
                collectionId = args.optStr("collectionId")?.let(Uuid::parse), sourceMetadataId = args.optStr("sourceMetadataId")?.let(Uuid::parse),
                conversationId = args.optStr("conversationId")?.let(Uuid::parse), expires = args.optStr("expires")?.let(ZonedDateTime::parse), tags = strings(args, "tags"),
            )).toJson()
            "get" -> api.get(Uuid.parse(args.str("id")))?.toJson() ?: JsonNull
            "search" -> JsonArray(api.search(MemoryQuery(
                text = args.optStr("text") ?: "", collectionIds = strings(args, "collectionIds").map(Uuid::parse),
                ids = strings(args, "ids").map(Uuid::parse), tags = strings(args, "tags"),
                personal = args["personal"]?.jsonPrimitive?.boolean ?: true,
                offset = args["offset"]?.jsonPrimitive?.long ?: 0, limit = args.optInt("limit") ?: 5,
            )).map { it.toJson() })
            "update" -> api.update(Uuid.parse(args.str("id")), args.str("expectedRevision"), args.str("statement"),
                args["refreshSource"]?.jsonPrimitive?.boolean ?: false).toJson()
            "forget" -> buildJsonObject { put("forgotten", api.forget(Uuid.parse(args.str("id")), args.str("expectedRevision"))) }
            else -> error("Unknown memory action")
        }
        CallToolResult(content = listOf(TextContent(result.toString())))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { CallToolResult(content = listOf(TextContent("Error: ${failure.message}")), isError = true) }

    private fun strings(args: Map<String, JsonElement>, key: String) = (args[key] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    private fun Map<String, JsonElement>.optStr(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun Map<String, JsonElement>.str(key: String) = requireNotNull(optStr(key)) { "Missing $key" }
    private fun Map<String, JsonElement>.optInt(key: String) = this[key]?.jsonPrimitive?.intOrNull

    private fun IMemoryFragment.toJson() = buildJsonObject {
        put("id", id.toString()); put("version", version); put("revision", revision); put("statement", statement)
        put("sourceChanged", sourceChanged); put("tags", JsonArray(tags.map(::JsonPrimitive)))
        put("created", created.toString()); put("modified", modified?.toString())
        putJsonObject("provenance") {
            put("scope", provenance.scope.name); put("ownerId", provenance.ownerId.toString())
            put("collectionId", provenance.collectionId?.toString()); put("sourceMetadataId", provenance.sourceMetadataId?.toString())
            put("sourceVersion", provenance.sourceVersion); put("sourceRevision", provenance.sourceRevision)
            put("conversationId", provenance.conversationId?.toString()); put("expires", provenance.expires?.toString())
        }
    }
}
