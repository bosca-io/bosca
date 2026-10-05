package bosca.communications.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Delivery channels a notification preference can apply to.
 * Mirrors the ``communications.channel`` database enum.
 */
@Serializable
enum class DeliveryChannel {
    EMAIL,
    PUSH,
}

/**
 * Per-user notification preference for a specific channel and
 * notification type. Controls whether a user receives notifications
 * of a given type on a given channel. Non-optional types
 * (see [NotificationType.optional]) bypass preference checks on
 * every channel.
 */
@Serializable
data class NotificationPreference(
    @ColumnName("profile_id")
    val profileId: UUID,
    val channel: DeliveryChannel,
    val type: String,
    @ColumnName("opted_out")
    val optedOut: Boolean = false,
    @ColumnName("updated_at")
    val updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * Keys of the seeded, well-known notification types, for sending
 * code that references them directly. Admins can define additional
 * types at runtime; this object only names the built-ins. A well-known
 * default is not necessarily a protected [NotificationType.system] type.
 */
object NotificationTypeKeys {
    const val TRANSACTIONAL = "transactional"
    const val DIGEST = "digest"
    const val MARKETING = "marketing"
    const val SECURITY = "security"
    const val GIT_ACTIVITY = "git_activity"
    const val WORKOPS_ACTIVITY = "workops_activity"
    const val SOCIAL_ACTIVITY = "social_activity"
}
