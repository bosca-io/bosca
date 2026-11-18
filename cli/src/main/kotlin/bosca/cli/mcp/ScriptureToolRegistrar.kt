package bosca.cli.mcp

import bosca.cli.api.ContentScripture
import bosca.graphql.gen.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** Exposes Scripture reads without delegating inference to another Kit conversation. */
object ScriptureToolRegistrar {
    val tool = Tool(
        name = "content_scripture",
        description = "Retrieve Scripture from installed Bible translations. Use list to discover accessible translations and variants, " +
            "books to list their books and chapters, references to resolve a human reference without downloading text, and verses to retrieve exact passage components and attribution. " +
            "Use only the returned text when quoting; never reconstruct missing Scripture from memory or substitute another translation. " +
            "Preserve copyright and citation. verses enforces the source's AI_CONTEXT permission. " +
            "Pass id, version and variant from discovery to select the exact edition. An empty result or error is not retrieved Scripture.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("action") { put("type", "string"); put("enum", JsonArray(listOf("list", "books", "references", "verses").map(::JsonPrimitive))) }
            putJsonObject("id") { put("type", "string"); put("description", "Bible metadata UUID") }
            putJsonObject("version") { put("type", "integer"); put("description", "Content version; defaults to current") }
            putJsonObject("variant") { put("type", "string"); put("description", "Exact edition variant; defaults to the installed default") }
            putJsonObject("reference") { put("type", "string"); put("description", "Human-readable reference, such as John 3:16-18 or Psalm 23 (references/verses)") }
            putJsonObject("offset") { put("type", "integer"); put("minimum", 0) }
            putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 100); put("description", "List page size; default 20") }
        }, required = listOf("action")),
        annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true),
    )

    fun registerAll(server: Server, api: ContentScripture) {
        server.addTool(tool) { request -> handle(api, request.arguments ?: error("Missing arguments")) }
    }

    internal suspend fun handle(api: ContentScripture, args: Map<String, JsonElement>): CallToolResult = try {
        val action = args.required("action")
        val result = if (action == "list") {
            val offset = args["offset"]?.jsonPrimitive?.int ?: 0
            val limit = args["limit"]?.jsonPrimitive?.int ?: 20
            require(offset >= 0 && limit in 1..100) { "Use a nonnegative offset and a limit between 1 and 100" }
            Json.encodeToString(ListScriptureBiblesData.serializer(), api.list(offset, limit))
        } else {
            val id = Uuid.parse(args.required("id"))
            val version = args["version"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int
            require(version == null || version > 0) { "Version must be a positive integer" }
            val variant = args["variant"]?.jsonPrimitive?.contentOrNull
            when (action) {
                "books" -> api.books(id, version, variant).let {
                    requireNotNull(it.content.metadata?.bible) { "Bible not found or unavailable" }
                    Json.encodeToString(GetScriptureBooksData.serializer(), it)
                }
                "references" -> api.references(id, version, variant, args.required("reference")).let {
                    requireNotNull(it.content.metadata?.bible) { "Bible not found or unavailable" }
                    Json.encodeToString(ResolveScriptureReferencesData.serializer(), it)
                }
                "verses" -> api.verses(id, version, variant, args.required("reference")).let {
                    requireNotNull(it.content.metadata?.bible) { "Bible not found or unavailable" }
                    Json.encodeToString(GetScriptureVersesData.serializer(), it)
                }
                else -> error("Unknown Scripture action")
            }
        }
        CallToolResult(content = listOf(TextContent(result)))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) { CallToolResult(content = listOf(TextContent("Error: ${failure.message}")), isError = true) }

    private fun Map<String, JsonElement>.required(key: String): String =
        requireNotNull(this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }) { "Missing $key" }
}
