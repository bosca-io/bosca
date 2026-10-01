package bosca.segmentation.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Lifecycle status of a segment, controlling whether it is actively
 * being used for audience evaluation and notification targeting.
 */
@DbMapper(SegmentStatusMapper::class)
@Serializable
enum class SegmentStatus {
    /** The segment is being drafted and is not yet active. */
    DRAFT,
    /** The segment is active and its audience is being evaluated. */
    ACTIVE,
    /** The segment was previously active but has been paused. */
    PAUSED,
    /** The segment is archived and no longer in use. */
    ARCHIVED
}

object SegmentStatusMapper : EnumMapper<SegmentStatus>({ SegmentStatus.valueOf(it.uppercase()) })
