package bosca.communications.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Per-profile notification settings: quiet hours for push delivery.
 * [dndStartLocal] and [dndEndLocal] are HH:MM strings in the
 * profile's local time; [timeZone] is an IANA zone id. Quiet hours
 * only apply when all three are present.
 */
@Serializable
data class NotificationSettings(
    @ColumnName("profile_id")
    val profileId: UUID,
    @ColumnName("time_zone")
    val timeZone: String? = null,
    @ColumnName("dnd_start_local")
    val dndStartLocal: String? = null,
    @ColumnName("dnd_end_local")
    val dndEndLocal: String? = null,
    @ColumnName("updated_at")
    val updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
