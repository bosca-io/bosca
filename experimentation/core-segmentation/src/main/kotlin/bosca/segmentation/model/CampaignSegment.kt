package bosca.segmentation.model

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Join entity linking a [Campaign] to one of the [Segment]s
 * whose audience should receive it.
 */
@Serializable
data class CampaignSegment(
    @ColumnName("campaign_id")
    @Contextual
    val campaignId: UUID,
    @ColumnName("segment_id")
    @Contextual
    val segmentId: UUID
)
