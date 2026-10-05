package bosca.feeds.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.feeds.model.FeedSource
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Persistence for `feeds.feed_sources`. Maps the core [FeedSource] model directly. */
@Repository
interface FeedSourceRepository {

    @Query("select * from feeds.feed_sources where source_id = :sourceId and deleted is null")
    suspend fun get(sourceId: UUID): FeedSource?

    @Query("select * from feeds.feed_sources where deleted is null order by created offset :offset limit :limit")
    suspend fun getAll(offset: Int, limit: Int): List<FeedSource>

    @Query("select * from feeds.feed_sources where url = :url and deleted is null")
    suspend fun getByUrl(url: String): FeedSource?

    @Query(
        "select * from feeds.feed_sources where owner_profile_id = :ownerProfileId and deleted is null " +
            "order by created desc offset :offset limit :limit"
    )
    suspend fun getByOwner(ownerProfileId: UUID, offset: Int, limit: Int): List<FeedSource>

    @Query(
        "insert into feeds.feed_sources (source_id, enabled, owner_profile_id, url) " +
            "values (:sourceId, :enabled, :ownerProfileId, :url) returning *"
    )
    suspend fun add(feedSource: FeedSource): FeedSource

    @Query(
        "update feeds.feed_sources set enabled = :enabled, owner_profile_id = :ownerProfileId, " +
            "url = :url, modified = now() where source_id = :sourceId and deleted is null returning *"
    )
    suspend fun update(sourceId: UUID, enabled: Boolean, ownerProfileId: UUID?, url: String): FeedSource?

    @Query(
        "update feeds.feed_sources set enabled = :enabled, modified = now() " +
            "where source_id = :sourceId and deleted is null returning *"
    )
    suspend fun setEnabled(sourceId: UUID, enabled: Boolean): FeedSource?

    @Query("update feeds.feed_sources set deleted = now(), modified = now() where source_id = :sourceId and deleted is null")
    suspend fun softDelete(sourceId: UUID)

    @Query(
        "update feeds.feed_sources set etag = :etag, last_modified = :lastModified, modified = now() " +
            "where source_id = :sourceId and deleted is null"
    )
    suspend fun updateValidators(sourceId: UUID, etag: String?, lastModified: OffsetDateTime?)

    @Query(
        "update feeds.feed_sources set scheduled_job_id = :scheduledJobId, modified = now() " +
            "where source_id = :sourceId and deleted is null"
    )
    suspend fun setScheduledJob(sourceId: UUID, scheduledJobId: UUID?)
}
