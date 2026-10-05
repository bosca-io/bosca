package bosca.feeds.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.feeds.model.FeedItem
import bosca.serialization.UUID

/** Persistence for `feeds.feed_items` — the (source, GUID) → Metadata dedup mapping. */
@Repository
interface FeedItemRepository {

    @Query("select * from feeds.feed_items where source_id = :sourceId and guid = :guid")
    suspend fun get(sourceId: UUID, guid: String): FeedItem?

    @Query(
        "select * from feeds.feed_items where source_id = :sourceId " +
            "order by created desc offset :offset limit :limit"
    )
    suspend fun getBySource(sourceId: UUID, offset: Int, limit: Int): List<FeedItem>

    /**
     * A profile's assembled feed: items from every source the profile is subscribed to OR
     * owns (a live user source), newest-first. The union is a subquery so no list parameter is needed.
     */
    @Query(
        "select fi.* from feeds.feed_items fi where fi.source_id in (" +
            "select source_id from feeds.feed_subscriptions where profile_id = :profileId " +
            "union " +
            "select source_id from feeds.feed_sources where owner_profile_id = :profileId and deleted is null" +
            ") order by fi.created desc offset :offset limit :limit"
    )
    suspend fun getForProfile(profileId: UUID, offset: Int, limit: Int): List<FeedItem>

    @Query(
        "insert into feeds.feed_items (source_id, guid, metadata_id) " +
            "values (:sourceId, :guid, :metadataId) returning *"
    )
    suspend fun add(feedItem: FeedItem): FeedItem

    @Query("update feeds.feed_items set modified = now() where source_id = :sourceId and guid = :guid")
    suspend fun touch(sourceId: UUID, guid: String)
}
