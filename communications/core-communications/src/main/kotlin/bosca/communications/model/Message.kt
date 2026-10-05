package bosca.communications.model

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

enum class MessageChannel {
    EMAIL,
    PUSH,
}

@Serializable
data class Message(
    @Contextual val id: UUID = UUID.random(),
    val channels: List<MessageChannel>,
    /** Fallback subject for an untemplated message. BML owns rendered copy when selected. */
    val subject: String = "",
    val sender: UUID? = null, // profile id
    val recipients: List<UUID>, // profile id
    /** Fallback content for an untemplated message. BML owns rendered copy when selected. */
    val content: List<MessageContent> = emptyList(),
    val pushOptions: PushOptions? = null,
    /**
     * The notification-type key (the `notification_types` catalog) the preference gate and
     * unsubscribe scoping evaluate; null = uncategorized (the gate falls back to its defaults).
     */
    val type: String? = null,
    /**
     * When set, each requested channel renders from this BML message unit at send time. Email
     * uses its subject/HTML/text output; push uses its title/body output. Both share the same
     * typed payload, locale, published version, and [pushOptions] context.
     */
    val bmlTemplate: MessageBmlTemplate? = null,
) : IJobDefinition

/**
 * A reference to a hosted BML message unit: the message project's key, the template key within
 * it, and the typed [payload] decoded inside the compiled unit.
 */
@Serializable
data class MessageBmlTemplate(
    val project: String,
    val templateKey: String,
    @Contextual val payload: JsonElement? = null,
) {
    /** How the reference reads in logs/errors. */
    val label: String get() = "$project/$templateKey"
}

enum class MessageContentType {
    TEXT,
    HTML,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    PRAYER,
    ACTIVITY,
    METADATA,
    COLLECTION,
    LOCALIZATION,
    CALENDAR_EVENT,
    FEATURE_FLAG,
    EXPERIMENT,
    MENTION,
}

@Serializable
data class MessageContent(
    val type: MessageContentType,
    val content: String,
    @Contextual
    val attributes: JsonElement? = null
)
