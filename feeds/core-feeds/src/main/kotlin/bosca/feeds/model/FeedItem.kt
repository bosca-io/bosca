package bosca.feeds.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Maps an external feed item to the content `Metadata` it was ingested as, in `feeds.feed_items`.
 * The `(sourceId, guid)` pair is the dedup key (PK): re-fetching the same GUID edits the existing
 * Metadata rather than creating a duplicate. Content has no cross-module finder by source,
 * so this mapping lives in the feeds schema.
 */
@Serializable
data class FeedItem(
    @Contextual @ColumnName("source_id") val sourceId: UUID,
    val guid: String,
    @Contextual @ColumnName("metadata_id") val metadataId: UUID,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val modified: OffsetDateTime = OffsetDateTime.now(),
)
