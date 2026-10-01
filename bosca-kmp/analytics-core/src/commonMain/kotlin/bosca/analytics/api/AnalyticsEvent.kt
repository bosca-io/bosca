package bosca.analytics.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.SerialName

/** Immutable event used by the public API, offline store, and collector wire format. */
@Serializable
data class AnalyticsEvent(
    @SerialName("client_id")
    val clientId: String,
    val type: AnalyticsEventType,
    val created: Long,
    @SerialName("created_micros")
    val createdMicros: Int,
    val element: AnalyticsElement,
    val page: Page? = null,
    val error: ErrorInfo? = null,
) {
    val name: String
        get() = type.wireName

    /** Returns the flattened parameter representation used by legacy analytics adapters. */
    fun toParameters(): Map<String, JsonElement> = buildMap {
        put("type", JsonPrimitive(type.wireName))
        put("element_id", JsonPrimitive(element.id))
        put("element_type", JsonPrimitive(element.type))
        put("created", JsonPrimitive(created))
        element.extras.forEach { (key, value) -> put("extra_$key", JsonPrimitive(value)) }
        element.content.forEachIndexed { index, content -> addContent(index, content) }
        error?.let { addError(it) }
    }

    private fun MutableMap<String, JsonElement>.addContent(index: Int, content: ContentElement) {
        put("content_id_$index", JsonPrimitive(content.id))
        put("content_id_type_$index", JsonPrimitive(content.type))
        content.index?.let { put("content_id_index_$index", JsonPrimitive(it)) }
        content.percent?.let { put("content_id_percent_$index", JsonPrimitive(it)) }
    }

    private fun MutableMap<String, JsonElement>.addError(failure: ErrorInfo) {
        put("error_message", JsonPrimitive(failure.message))
        failure.type?.let { put("error_type", JsonPrimitive(it)) }
        put("error_fatal", JsonPrimitive(failure.fatal))
        failure.stackTrace?.let { put("error_stack_trace", JsonPrimitive(it)) }
        failure.code?.let { put("error_code", JsonPrimitive(it)) }
    }
}
