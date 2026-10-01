package bosca.community.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class PrayerLike(
    @ColumnName("prayer_id")
    val prayerId: UUID,
    @ColumnName("profile_id")
    val profileId: UUID,
    @ColumnName("liked_at")
    val likedAt: OffsetDateTime
)
