package bosca.community.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.security.model.PermissibleEntity
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

@DbMapper(PrayerStatusMapper::class)
@Serializable
enum class PrayerStatus {
    ACTIVE, CANCELLED, ANSWERED, PENDING, BLOCKED, PENDING_APPROVAL
}

object PrayerStatusMapper : EnumMapper<PrayerStatus>({ PrayerStatus.valueOf(it.uppercase()) })

@Serializable
data class Prayer(
    override val id: UUID,
    @ColumnName("profile_id")
    val profileId: UUID,
    val title: String,
    val content: JsonElement,
    val status: PrayerStatus,
    val created: OffsetDateTime,
    val modified: OffsetDateTime,
    @ColumnName("answered_at")
    val answeredAt: OffsetDateTime? = null,
    @ColumnName("last_activity_at")
    val lastActivityAt: OffsetDateTime,
    @ColumnName("prayer_action_count")
    val prayerActionCount: Int = 0,
    @ColumnName("like_count")
    val likeCount: Int = 0,
    @ColumnName("comment_count")
    val commentCount: Int = 0,
    @ColumnName("suppress_anniversaries")
    val suppressAnniversaries: Boolean = false,
    val attributes: JsonElement?
) : PermissibleEntity<UUID> {

    @Transient
    override val public: Boolean = false

    @Transient
    override val publicContent: Boolean = false

    @Transient
    override val publicList: Boolean = false

    @Transient
    override val publicSupplementary: Boolean = false

    @Transient
    override val isPublished: Boolean = status == PrayerStatus.ACTIVE || status == PrayerStatus.ANSWERED

    @Transient
    override val isAdvertised: Boolean = false

    @Transient
    override val isDeleted: Boolean = false
}
