package bosca.feeds.service

import bosca.content.metadata.model.Metadata
import bosca.feeds.model.RawFeedItem
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Ingests parsed feed items into Bosca as canonical content `Metadata`. Idempotent by the
 * item's external GUID per source: a new GUID creates a Metadata (+ a `feeds.feed_items` mapping); a
 * known GUID edits the existing Metadata in place. The raw fields (summary/author/published/content)
 * are stashed in the Metadata `attributes` pending normalization to TipTap.
 */
interface FeedIngestionService : Service {

    /** Create or update the content Metadata for [item] under [sourceId], deduped by GUID. */
    suspend fun ingest(sourceId: UUID, item: RawFeedItem): Metadata
}
