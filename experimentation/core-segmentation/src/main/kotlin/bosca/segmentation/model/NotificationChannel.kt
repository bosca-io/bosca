package bosca.segmentation.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * The delivery channel used to send targeted notifications to segment audiences.
 */
@DbMapper(NotificationChannelMapper::class)
@Serializable
enum class NotificationChannel {
    /** An in-app banner displayed to users matching the segment. */
    BANNER,
    /** An email blast sent to the segment audience. */
    EMAIL,
    /** A push notification delivered to mobile devices of segment members. */
    PUSH
}

object NotificationChannelMapper : EnumMapper<NotificationChannel>({ NotificationChannel.valueOf(it.uppercase()) })
