package bosca.cli.api

import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A downloadable image reference emitted by Kit in an assistant message. */
data class KitImageReference(
    val metadataId: Uuid,
    val alt: String,
    val url: String,
)

/** Protocol-neutral, user-visible content extracted from one completed Kit turn. */
data class KitResponse(
    val text: String,
    val displayEvents: List<JsonObject>,
    val images: List<KitImageReference>,
)

fun KitChatTurn.responseContent(): KitResponse {
    val assistantMessages = messages.filter(KitChatMessage::isAssistantMessage)
    return KitResponse(
        text = assistantMessages.mapNotNull(KitChatMessage::textContent).joinToString("\n").trim(),
        displayEvents = messages.mapNotNull { message -> message.event.displayEventOrNull() },
        images = assistantMessages.flatMap(KitChatMessage::imageReferences)
            .distinctBy(KitImageReference::metadataId),
    )
}

fun KitChatMessage.isAssistantMessage(): Boolean {
    if (author.equals("assistant", ignoreCase = true)) return true
    val type = (event as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
    return type?.contains("Assistant", ignoreCase = true) == true
}

/** Returns visible text without exposing model thoughts or internal tool traffic. */
fun KitChatMessage.textContent(): String? {
    val parts = (event as? JsonObject)?.get("parts") as? JsonArray ?: return null
    return parts.mapNotNull { element ->
        val part = element as? JsonObject ?: return@mapNotNull null
        if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
        if ("functionCall" in part || "functionResponse" in part) return@mapNotNull null
        part["text"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    }.joinToString("\n").takeIf(String::isNotBlank)
}

fun JsonElement?.displayEventOrNull(): JsonObject? =
    (this as? JsonObject)?.takeIf { event ->
        event["type"]?.jsonPrimitive?.contentOrNull in DISPLAY_EVENT_TYPES
    }

private fun KitChatMessage.imageReferences(): List<KitImageReference> =
    textContent().orEmpty().let { text ->
        MARKDOWN_IMAGE.findAll(text).mapNotNull { match ->
            val url = match.groupValues[2]
            val id = METADATA_ID.find(url)?.groupValues?.get(1)?.let { value ->
                runCatching { Uuid.parse(value) }.getOrNull()
            } ?: return@mapNotNull null
            KitImageReference(id, match.groupValues[1], url)
        }.toList()
    }

private val DISPLAY_EVENT_TYPES = setOf("tool-display", "graphql-result")
private val MARKDOWN_IMAGE = Regex("""!\[([^]]*)]\(([^)\s]+)(?:\s+[\"'][^\"']*[\"'])?\)""")
private val METADATA_ID = Regex("""[?&]id=([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:&|$)""")
