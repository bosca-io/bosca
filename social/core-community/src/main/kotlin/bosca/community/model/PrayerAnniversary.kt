package bosca.community.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

@Serializable
data class PrayerAnniversary(
    @ColumnName("prayer_id")
    val prayerId: UUID,
    val milestone: String,
    @ColumnName("posted_at")
    val postedAt: OffsetDateTime
)
