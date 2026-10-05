package bosca.community.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class PrayerShare(
    @ColumnName("prayer_id")
    val prayerId: UUID,
    @ColumnName("profile_id")
    val profileId: UUID,
    @ColumnName("shared_at")
    val sharedAt: OffsetDateTime
)
