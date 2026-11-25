package bosca.ai.kit.harness.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.Role
import com.agentclientprotocol.model.ToolCallContent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Display content from the pinned Codex adapter's MCP envelope; opaque input/output is never a display field. */
internal object CodexAcpToolContent {
    private val json = Json { ignoreUnknownKeys = true }

    fun extract(input: JsonElement?, output: JsonElement?): List<ToolCallContent>? {
        val identity = input as? JsonObject ?: return null
        if (identity.string("server").isNullOrBlank() || identity.string("tool").isNullOrBlank()) return null
        val result = (output as? JsonObject)?.get("result") as? JsonObject ?: return null
        val content = result["content"] as? JsonArray ?: return null
        val blocks = content.mapNotNull { value ->
            try {
                val block = json.decodeFromJsonElement(ContentBlock.serializer(), withoutMetadata(value))
                block.takeUnless { it.annotations?.audience?.let { audience -> Role.USER !in audience } == true }
            } catch (_: SerializationException) {
                ContentBlock.Text("The tool returned an unsupported content block.")
            } catch (_: IllegalArgumentException) {
                ContentBlock.Text("The tool returned an unsupported content block.")
            }
        }
        // MCP clients can require a text copy of structured resources. Display the resource once.
        val resourceTexts = blocks.filterIsInstance<ContentBlock.Resource>()
            .mapNotNull { (it.resource as? EmbeddedResourceResource.TextResourceContents)?.text }.toSet()
        return blocks.filterNot { it is ContentBlock.Text && it.text in resourceTexts }
            .distinct().map { ToolCallContent.Content(it) }
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun withoutMetadata(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys { it != "_meta" }.mapValues { withoutMetadata(it.value) })
        is JsonArray -> JsonArray(value.map(::withoutMetadata))
        else -> value
    }
}
