package bosca.segmentation.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A named audience segment used to target groups of profiles for banners,
 * email blasts, and push notifications.
 *
 * Segments can be either static (a fixed list of profile IDs managed via
 * [SegmentMember]) or dynamic (membership is computed at evaluation time
 * by executing the referenced [analyticsQueryId] against the analytics
 * data warehouse via Trino).
 */
@BatchKey("id")
@Serializable
data class Segment(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val description: String = "",
    val type: SegmentType,
    val status: SegmentStatus = SegmentStatus.DRAFT,
    @ColumnName("analytics_query_id")
    @Contextual
    val analyticsQueryId: UUID? = null,
    @Contextual
    val configuration: JsonElement? = null,
    @ColumnName("scheduled_job_id")
    @Contextual
    val scheduledJobId: UUID? = null,
    @ColumnName("last_evaluated")
    @Contextual
    val lastEvaluated: OffsetDateTime? = null,
    @ColumnName("member_count")
    val memberCount: Long = 0,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now()
)
