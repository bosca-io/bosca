package bosca.segmentation.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import kotlinx.serialization.Serializable

/**
 * Determines how a segment's audience membership is computed.
 *
 * Static segments have a fixed list of profile IDs, while dynamic segments
 * re-evaluate membership by running an analytics query against the data warehouse.
 */
@DbMapper(SegmentTypeMapper::class)
@Serializable
enum class SegmentType {
    /** Membership is a manually curated list of profile IDs. */
    STATIC,
    /** Membership is computed at evaluation time by executing an analytics query. */
    DYNAMIC,
    /** Implicitly includes all users, including anonymous visitors. No explicit membership required. */
    EVERYONE
}

object SegmentTypeMapper : EnumMapper<SegmentType>({ SegmentType.valueOf(it.uppercase()) })
