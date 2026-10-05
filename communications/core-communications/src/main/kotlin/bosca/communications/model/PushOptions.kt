package bosca.communications.model

import kotlinx.serialization.Serializable

/**
 * Platform-specific push notification options that control delivery behavior,
 * presentation, and payload data beyond the basic title and body.
 *
 * These options are mapped to the appropriate platform-specific fields
 * when sending via FCM (Android/Web/Desktop) or APNs (iOS).
 */
@Serializable
data class PushOptions(
    /** A publicly accessible URL for the notification image. */
    val imageUrl: String? = null,
    /** Action performed when the notification itself is opened. */
    val defaultAction: PushAction? = null,
    /** Ordered secondary actions that a notification client may present alongside the notification. */
    val actions: List<PushAction> = emptyList(),
    /** Delivery priority: "NORMAL" (default, may be batched) or "HIGH" (immediate wake). */
    val priority: String? = null,
    /** Custom sound name, or "default" for the system default notification sound. */
    val sound: String? = null,
    /** App icon badge count to display. */
    val badge: Int? = null,
    /** Time-to-live in seconds — how long the message is retained if the device is offline. */
    val ttl: Long? = null,
    /** Android notification channel ID for Android 8+. */
    val androidChannelId: String? = null,
    /** Android notification tag — replaces existing notifications with the same tag. */
    val androidTag: String? = null,
    /** When offline, only the latest message per collapse key is delivered. */
    val collapseKey: String? = null,
    /** iOS thread identifier for grouping notifications in the notification center. */
    val threadId: String? = null,
    /** iOS notification category for custom action buttons. */
    val category: String? = null,
    /** iOS interruption level controlling how the notification is presented (passive, active, time-sensitive, critical). */
    val interruptionLevel: String? = null,
    /** iOS relevance score for sorting priority in the notification summary (0.0 to 1.0). */
    val relevanceScore: Double? = null,
    /** iOS flag allowing a Notification Service Extension to modify the notification before display. */
    val mutableContent: Boolean? = null,
    /** iOS flag triggering a background fetch in the app when the notification is received. */
    val contentAvailable: Boolean? = null,
    /** Arbitrary key-value pairs included in the notification data payload, accessible to the client app. */
    val data: Map<String, String>? = null,
    /** Structured content used by rich-notification clients and notification service extensions. */
    val richContent: PushRichContent? = null,
)

/**
 * A provider-neutral notification action. The stable [id], destination, and [data] are supplied by
 * the producer; a BML message template selects the action and supplies its localized [label].
 */
@Serializable
data class PushAction(
    val id: String,
    val label: String? = null,
    val url: String? = null,
    val destructive: Boolean = false,
    val data: Map<String, String>? = null,
)

/** Versioned action envelope serialized into provider data for notification clients. */
@Serializable
data class PushActionSet(
    val defaultAction: PushAction? = null,
    val actions: List<PushAction> = emptyList(),
)

/** Rich push content carried in the provider data payload alongside the visible notification. */
@Serializable
data class PushRichContent(
    /** Remotely fetched media associated with the current notification. */
    val attachments: List<PushAttachment> = emptyList(),
    /** The current incoming communication. Clients own conversation history and grouping. */
    val conversation: PushConversation? = null,
)

/** A remote media attachment. Providers receive the URL, not the media bytes. */
@Serializable
data class PushAttachment(
    /** Stable identifier for associating the attachment with application content. */
    val id: String? = null,
    val url: String,
    val type: PushAttachmentType = PushAttachmentType.IMAGE,
    val mediaType: String? = null,
    val altText: String? = null,
)

/** Media kinds understood by rich-notification clients. */
@Serializable
enum class PushAttachmentType {
    IMAGE,
    VIDEO,
    AUDIO,
}

/**
 * The one incoming message required to build a native conversation notification.
 *
 * This deliberately contains no history. Android and iOS accumulate prior messages locally so
 * delivery retries, provider payload limits, and out-of-order pushes cannot duplicate a server-
 * assembled preview.
 */
@Serializable
data class PushConversation(
    /** Stable channel or conversation identifier. */
    val id: String,
    val title: String? = null,
    /** Stable identifier for this message, such as a channel sequence or message UUID. */
    val messageId: String,
    val senderId: String? = null,
    /** Display name when it is known; clients own any localized unknown-sender fallback. */
    val senderName: String? = null,
    val senderImageUrl: String? = null,
    val body: String,
    val sentAtEpochMilliseconds: Long? = null,
    /** Whether this is a named group conversation rather than a direct conversation. */
    val groupConversation: Boolean = false,
)
