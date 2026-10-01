package bosca.feeds.service

import bosca.serialization.UUID
import bosca.service.Service

/** Outcome of a single source fetch. */
data class FeedFetchResult(
    val notModified: Boolean,
    val ingested: Int,
)

/**
 * Fetches one feed source: resolves its config + auth secret, does an authenticated
 * conditional GET, and on a fresh response parses and ingests each item, then stores
 * the new ETag/Last-Modified validators. A 304 is a no-op.
 */
interface FeedFetchService : Service {

    /**
     * Fetch the source. When [force] is true the conditional-GET validators (ETag / Last-Modified) are
     * NOT sent, so the origin returns the full feed even when unchanged — the manual "fetch now"
     * trigger. Dedup by GUID still prevents duplicate items.
     */
    suspend fun fetch(sourceId: UUID, force: Boolean = false): FeedFetchResult
}
