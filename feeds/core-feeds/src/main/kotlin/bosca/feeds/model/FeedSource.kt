package bosca.feeds.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A feed source's record in `feeds.feed_sources`, keyed by the content `Source` id (`sourceId`). The
 * declarative spec — type / endpoint / cron / auth — is the [FeedConfiguration] stored on the content
 * `Source.configuration`; this record carries only the indexed, queryable operational fields Postgres
 * needs: the unique `url` (overlap rule), `enabled`, the owner, the scheduled-job
 * link, and the conditional-GET validators. Read the configuration via
 * `FeedSourceService.getConfiguration`.
 */
@Serializable
data class FeedSource(
    @Contextual @ColumnName("source_id") val sourceId: UUID = UUID.NIL,
    val enabled: Boolean = true,
    @Contextual @ColumnName("owner_profile_id") val ownerProfileId: UUID? = null,
    @ColumnName("url") val url: String,
    @Contextual @ColumnName("scheduled_job_id") val scheduledJobId: UUID? = null,
    val etag: String? = null,
    @Contextual @ColumnName("last_modified") val lastModified: OffsetDateTime? = null,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val deleted: OffsetDateTime? = null,
)

/**
 * Input to create a feed source: the content `Source` identity + its typed [FeedConfiguration]
 * (the non-secret auth descriptor) + an optional [authSecret]. The secret is routed to
 * `ConfigurationService` and never stored in `Source.configuration`.
 */
@Serializable
data class FeedSourceInput(
    val name: String,
    val description: String,
    val configuration: FeedConfiguration,
    val authSecret: String? = null,
)
