package bosca.feeds.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A profile's subscription to a feed source (`feeds.feed_subscriptions`), keyed by
 * (`profileId`, `sourceId`). Operational model only — subscriptions surface to clients as the
 * subscribed [FeedSource], not as this record.
 */
@Serializable
data class FeedSubscription(
    @Contextual @ColumnName("profile_id") val profileId: UUID,
    @Contextual @ColumnName("source_id") val sourceId: UUID,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
)
