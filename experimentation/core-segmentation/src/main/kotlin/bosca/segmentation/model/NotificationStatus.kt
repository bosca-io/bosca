package bosca.segmentation.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Tracks the lifecycle of a targeted notification from draft through delivery.
 */
@DbMapper(NotificationStatusMapper::class)
@Serializable
enum class NotificationStatus {
    /** The notification is being authored and has not been scheduled. */
    DRAFT,
    /** The notification has been scheduled for future delivery. */
    SCHEDULED,
    /** The notification is currently being dispatched to the audience. */
    SENDING,
    /** The notification has been fully delivered. */
    SENT,
    /** The campaign is currently active and being displayed or has been reactivated after cancellation. */
    ACTIVE,
    /** The notification delivery failed. */
    FAILED,
    /** The notification was cancelled before delivery completed. */
    CANCELLED
}

object NotificationStatusMapper : EnumMapper<NotificationStatus>({ NotificationStatus.valueOf(it.uppercase()) })
