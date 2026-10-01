package bosca.community.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PrayerComment(
    val id: Long,
    @ColumnName("prayer_id")
    val prayerId: UUID,
    @ColumnName("parent_id")
    val parentId: Long? = null,
    @ColumnName("profile_id")
    val profileId: UUID,
    val content: String,
    val created: OffsetDateTime,
    val modified: OffsetDateTime,
    val deleted: Boolean = false,
    val attributes: JsonElement? = null
)
