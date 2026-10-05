package bosca.profile.relationship.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Lifecycle state of a request to establish a profile relationship. */
@DbMapper(ProfileRelationshipRequestStatusMapper::class)
@Serializable
enum class ProfileRelationshipRequestStatus {
    PENDING,
    APPROVED,
    DECLINED,
    CANCELLED,
}

/** Maps relationship-request status values between PostgreSQL and Kotlin. */
object ProfileRelationshipRequestStatusMapper :
    EnumMapper<ProfileRelationshipRequestStatus>({ ProfileRelationshipRequestStatus.valueOf(it.uppercase()) })

/**
 * A request from [requesterProfileId] to [targetProfileId] to establish a directional relationship.
 * Approved requests are retained as lifecycle history; the actual relationship is stored separately.
 */
@Serializable
data class ProfileRelationshipRequest(
    @Contextual
    val id: UUID,
    @ColumnName("requester_profile_id")
    @Contextual
    val requesterProfileId: UUID,
    @ColumnName("target_profile_id")
    @Contextual
    val targetProfileId: UUID,
    val type: String,
    val attributes: JsonElement? = null,
    val status: ProfileRelationshipRequestStatus,
    val version: Long,
    val created: OffsetDateTime,
    val modified: OffsetDateTime,
)
