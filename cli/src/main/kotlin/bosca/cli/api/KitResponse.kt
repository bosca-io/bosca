package bosca.cli.api

import bosca.ai.kit.harness.KitHarnessTurnEvent
import bosca.graphql.client.GraphQLJson
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
    val harnessTurns: List<KitHarnessTurnEvent>,
    val status: String,
) {
    private val unconfirmed: Boolean get() = harnessTurns.any { it.status !in setOf("COMPLETED", "CANCELLED", "FAILED", "INTERRUPTED") }
    val failed: Boolean get() = status in setOf("FAILED", "INTERRUPTED") || unconfirmed
    val cancelled: Boolean get() = status == "CANCELLED"
    val empty: Boolean get() = text.isBlank() && displayEvents.isEmpty() && images.isEmpty() &&
        harnessTurns.all { it.content.isEmpty() && it.tools.isEmpty() }

    val outcomeText: String? get() = when (status) {
        "CANCELLED" -> "Kit's turn was cancelled."
        "INTERRUPTED" -> "Kit's turn was interrupted; the outcome could not be confirmed."
        "FAILED" -> "Kit failed to complete the request."
        else -> if (unconfirmed) "Kit has not published a terminal outcome; its work may still be running." else null
    }
}

fun KitChatTurn.responseContent(): KitResponse {
    val assistantMessages = messages.filter(KitChatMessage::isAssistantMessage)
    val turns = messages.harnessTurns()
    val text = assistantMessages.mapNotNull(KitChatMessage::textContent).joinToString("\n").trim()
    return KitResponse(
        text = text.ifBlank { turns.joinToString("\n") { it.text }.trim() },
        displayEvents = messages.mapNotNull { message -> message.event.displayEventOrNull() },
        images = assistantMessages.flatMap(KitChatMessage::imageReferences)
            .distinctBy(KitImageReference::metadataId),
        harnessTurns = turns,
        status = turns.lastOrNull()?.status ?: session.status.name,
    )
}

internal fun List<KitChatMessage>.harnessTurns(): List<KitHarnessTurnEvent> =
    mapNotNull { it.event.harnessTurnOrNull() }.groupBy { it.turnId }.values.map { updates ->
        updates.maxWith(compareBy(KitHarnessTurnEvent::generation, KitHarnessTurnEvent::epoch, KitHarnessTurnEvent::revision))
    }

internal fun JsonElement?.harnessTurnOrNull(): KitHarnessTurnEvent? =
    (this as? JsonObject)?.takeIf { (it["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == "harness-turn" }
        ?.let { GraphQLJson.decodeFromJsonElement(KitHarnessTurnEvent.serializer(), it) }

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
