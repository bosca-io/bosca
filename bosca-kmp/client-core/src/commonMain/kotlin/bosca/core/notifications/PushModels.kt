package bosca.core.notifications

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A device platform understood by Bosca's device-registration API. */
enum class PushDevicePlatform {
    IOS,
    ANDROID,
    WEB,
    DESKTOP,
}

/** The service that issued and accepts a native push token. */
enum class PushTokenTransport {
    FCM,
    APNS,
}

/** A server-defined notification type used for presentation and preference controls. */
data class NotificationTypeDefinition(
    /** Stable key used as the native notification-channel identifier. */
    val key: String,
    /** User-facing channel name. */
    val name: String,
    /** Optional user-facing explanation of notifications delivered to this channel. */
    val description: String?,
)

/** Data key containing the server notification-type key for an incoming notification. */
const val NOTIFICATION_TYPE_KEY = "notification_type"

/** Data key containing the versioned notification action envelope. */
const val NOTIFICATION_ACTIONS_KEY = "bosca_push_actions_v1"

/** Data key containing the current message and media required for rich native presentation. */
const val RICH_PUSH_CONTENT_KEY = "bosca_rich_push_v1"

/** Data keys carrying presentation fields for Android data-only notifications. */
const val ANDROID_NOTIFICATION_IMAGE_KEY = "bosca_android_notification_image"
const val ANDROID_NOTIFICATION_BADGE_KEY = "bosca_android_notification_badge"

/** Intent/data key identifying the notification action selected by the user. */
const val NOTIFICATION_SELECTED_ACTION_ID_KEY = "bosca_notification_action_id"

/** Data key set when a notification was opened by the user. */
const val NOTIFICATION_OPENED_KEY = "bosca_notification_opened"

/** A remote notification received by the platform push provider. */
data class PushMessage(
    val id: String?,
    val title: String?,
    val body: String?,
    val data: Map<String, String> = emptyMap(),
)

/** One producer-routed, template-labeled notification action. */
@Serializable
data class PushNotificationAction(
    val id: String,
    val label: String? = null,
    val url: String? = null,
    val destructive: Boolean = false,
    val data: Map<String, String>? = null,
)

/** Default notification-open behavior plus ordered secondary actions. */
@Serializable
data class PushNotificationActionSet(
    val defaultAction: PushNotificationAction? = null,
    val actions: List<PushNotificationAction> = emptyList(),
)

/** Structured rich content for the current notification only. */
@Serializable
data class PushRichContent(
    val attachments: List<PushAttachment> = emptyList(),
    val conversation: PushConversation? = null,
)

/** One remotely fetchable attachment associated with the current message. */
@Serializable
data class PushAttachment(
    val id: String? = null,
    val url: String,
    val type: PushAttachmentType = PushAttachmentType.IMAGE,
    val mediaType: String? = null,
    val altText: String? = null,
)

@Serializable
enum class PushAttachmentType {
    IMAGE,
    VIDEO,
    AUDIO,
}

/** The one incoming message used to update a device-owned conversation notification. */
@Serializable
data class PushConversation(
    val id: String,
    val title: String? = null,
    val messageId: String,
    val senderId: String? = null,
    val senderName: String? = null,
    val senderImageUrl: String? = null,
    val body: String,
    val sentAtEpochMilliseconds: Long? = null,
    val groupConversation: Boolean = false,
)

/** Decodes a provider action envelope without allowing malformed notification data to crash the app. */
fun decodePushNotificationActionSet(value: String?): PushNotificationActionSet? =
    value?.takeIf { it.isNotBlank() }?.let {
        runCatching { pushActionJson.decodeFromString(PushNotificationActionSet.serializer(), it) }
            .getOrNull()
            ?.takeIf { actionSet ->
                val actions = listOfNotNull(actionSet.defaultAction) + actionSet.actions
                actions.all { action -> action.id.isNotBlank() } &&
                    actions.map { action -> action.id }.distinct().size == actions.size
            }
    }

/** Actions carried by this notification, if the provider data contained a valid envelope. */
fun PushMessage.actionSet(): PushNotificationActionSet? =
    decodePushNotificationActionSet(data[NOTIFICATION_ACTIONS_KEY])

/** Current rich-presentation content, or null when the provider data is absent or malformed. */
fun PushMessage.richContent(): PushRichContent? =
    data[RICH_PUSH_CONTENT_KEY]?.takeIf { it.isNotBlank() }?.let {
        runCatching { pushActionJson.decodeFromString(PushRichContent.serializer(), it) }
            .getOrNull()
            ?.takeIf { content ->
                content.attachments.all { attachment -> attachment.url.isNotBlank() } &&
                    content.conversation?.let { conversation ->
                        conversation.id.isNotBlank() && conversation.messageId.isNotBlank()
                    } != false
            }
    }

/** The first image selected for native presentation, including a standalone producer image. */
fun PushMessage.presentationImage(): PushAttachment? =
    richContent()?.attachments?.firstOrNull { it.type == PushAttachmentType.IMAGE }
        ?: data[ANDROID_NOTIFICATION_IMAGE_KEY]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { PushAttachment(url = it) }

/** Optional non-negative application badge/count selected by the producer. */
fun PushMessage.presentationBadge(): Int? =
    data[ANDROID_NOTIFICATION_BADGE_KEY]?.toIntOrNull()?.takeIf { it >= 0 }

private val pushActionJson = Json { ignoreUnknownKeys = true }

/** Current state of this installation's provider-token registration. */
sealed interface PushRegistrationState {
    /** The current platform does not expose a remote-push token provider. */
    data object Unavailable : PushRegistrationState

    /** No provider token is currently registered for an associated device. */
    data object Unregistered : PushRegistrationState

    /** A token is currently being synchronized with Bosca. */
    data object Registering : PushRegistrationState

    /** The device is registered, but the provider has not issued a token yet. */
    data object AwaitingToken : PushRegistrationState

    /** The provider token is registered with Bosca. */
    data object Registered : PushRegistrationState

    /** Registration failed and can be retried without losing the prior state. */
    data class Failed(val message: String) : PushRegistrationState
}
