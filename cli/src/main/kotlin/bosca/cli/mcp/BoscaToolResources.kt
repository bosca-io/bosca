package bosca.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.uuid.Uuid

/** Standard MCP resources supplement the tools' JSON text without fetching or widening access to any entity. */
internal object BoscaToolResources {
    fun metadata(action: String, text: String): CallToolResult {
        val content = mutableListOf<ContentBlock>(TextContent(text))
        if (action in setOf("get", "create", "edit", "set_content", "set_attributes", "set_ready", "get_document", "get_guide")) {
            val value = Json.parseToJsonElement(text) as? JsonObject
            value?.uuid("id")?.let { id ->
                content += ResourceLink(name = value.string("title") ?: value.string("name") ?: "Content",
                    uri = "bosca://content/metadata/$id", mimeType = value.string("contentType"))
            }
        }
        return CallToolResult(content = content)
    }

    fun analytics(kind: String, args: Map<String, JsonElement>, text: String): CallToolResult {
        val content = mutableListOf<ContentBlock>(TextContent(text))
        val action = args.string("action")
        if (kind == "queries" && action == "execute") {
            val reference = args.uuid("id")?.toString() ?: "by-key/${encodeSegment(requireNotNull(args.string("key")))}"
            content += EmbeddedResource(TextResourceContents(text = text,
                uri = "bosca://analytics/queries/$reference/results", mimeType = "application/json"))
        } else if (action in setOf("get", "create", "update")) {
            val value = Json.parseToJsonElement(text) as? JsonObject
            value?.uuid("id")?.let { id ->
                content += ResourceLink(name = value.string("name") ?: value.string("key") ?: "Analytics artifact",
                    uri = "bosca://analytics/$kind/$id")
            }
        }
        return CallToolResult(content = content)
    }

    private fun Map<String, JsonElement>.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun Map<String, JsonElement>.uuid(key: String) = string(key)?.let(Uuid::parseOrNull)
    private fun encodeSegment(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
