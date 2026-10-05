package bosca.segmentation.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Associates a profile with a segment, representing that the profile
 * is a member of the segment's target audience.
 *
 * For static segments, members are added and removed manually. For dynamic
 * segments, the membership table is refreshed each time the segment's
 * analytics query is evaluated.
 */
@Serializable
data class SegmentMember(
    @ColumnName("segment_id")
    @Contextual
    val segmentId: UUID,
    @ColumnName("profile_id")
    @Contextual
    val profileId: UUID,
    @ColumnName("added_at")
    @Contextual
    val addedAt: OffsetDateTime = OffsetDateTime.now()
)
