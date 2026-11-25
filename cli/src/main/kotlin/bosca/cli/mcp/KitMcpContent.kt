package bosca.cli.mcp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Explicit mapping keeps MCP content typed and avoids reflection-dependent cross-protocol conversion. */
internal fun ContentBlock.toMcpContent(meta: JsonObject): io.modelcontextprotocol.kotlin.sdk.types.ContentBlock = when (this) {
    is ContentBlock.Text -> TextContent(text, meta = meta)
    is ContentBlock.Image -> ImageContent(data, mimeType, meta = uri?.let { JsonObject(meta + ("uri" to JsonPrimitive(it))) } ?: meta)
    is ContentBlock.Audio -> AudioContent(data, mimeType, meta = meta)
    is ContentBlock.ResourceLink -> ResourceLink(name = name, uri = uri, title = title, description = description,
        mimeType = mimeType, size = size, meta = meta)
    is ContentBlock.Resource -> EmbeddedResource(when (val value = resource) {
        is EmbeddedResourceResource.TextResourceContents -> TextResourceContents(text = value.text, uri = value.uri, mimeType = value.mimeType)
        is EmbeddedResourceResource.BlobResourceContents -> BlobResourceContents(blob = value.blob, uri = value.uri, mimeType = value.mimeType)
    }, meta = meta)
}
