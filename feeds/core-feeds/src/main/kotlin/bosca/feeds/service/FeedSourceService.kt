package bosca.feeds.service

import bosca.feeds.model.FeedConfiguration
import bosca.feeds.model.FeedSource
import bosca.feeds.model.FeedSourceInput
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages feed sources. A feed source is the existing content `Source` whose `configuration` carries
 * a typed [FeedConfiguration] (the non-secret auth descriptor), paired with the `feeds.feed_sources`
 * operational record ([FeedSource]); the auth secret lives in `ConfigurationService`. There is no
 * standalone source entity.
 */
interface FeedSourceService : Service {

    /**
     * Create a feed source: writes the content `Source` (configuration) + the [FeedSource] record
     * atomically, and stores [FeedSourceInput.authSecret] (if any) in `ConfigurationService`.
     */
    suspend fun create(input: FeedSourceInput): FeedSource

    /** Edit a feed source's configuration (+ optional new auth secret). */
    suspend fun update(sourceId: UUID, input: FeedSourceInput): FeedSource

    /** Enable or disable a feed source; null if missing / soft-deleted. */
    suspend fun setEnabled(sourceId: UUID, enabled: Boolean): FeedSource?

    /** Soft-delete a feed source and remove its auth secret; false if it did not exist. */
    suspend fun delete(sourceId: UUID): Boolean

    /**
     * Enqueue an immediate fetch of the source onto the feeds queue, bypassing its schedule — a manual
     * operator trigger. Returns false if the source does not exist. The fetch runs asynchronously on the
     * feeds runner, exactly like a scheduled fetch.
     */
    suspend fun fetchNow(sourceId: UUID): Boolean

    /** The operational record by its content `Source` id, or null if missing / soft-deleted. */
    suspend fun get(sourceId: UUID): FeedSource?

    /** All feed source records, paged. */
    suspend fun getAll(offset: Int, limit: Int): List<FeedSource>

    /** The feed source records owned by [ownerProfileId] (user-owned sources), newest-first, paged. */
    suspend fun getByOwner(ownerProfileId: UUID, offset: Int, limit: Int): List<FeedSource>

    /** The decoded [FeedConfiguration] from the content `Source.configuration`, or null if not a feed source. */
    suspend fun getConfiguration(sourceId: UUID): FeedConfiguration?

    /** The outbound auth secret for a source from `ConfigurationService`, or null if none is set. */
    suspend fun getAuthSecret(sourceId: UUID): String?

    /** Persist the conditional-GET validators (ETag / Last-Modified) after a fetch. */
    suspend fun updateValidators(sourceId: UUID, etag: String?, lastModified: OffsetDateTime?)
}
