package bosca.feeds.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.feeds.model.FeedSource
import bosca.feeds.model.FeedSubscription
import bosca.serialization.UUID

/** Persistence for `feeds.feed_subscriptions` — a profile's subscriptions to feed sources. */
@Repository
interface FeedSubscriptionRepository {

    @Query("select * from feeds.feed_subscriptions where profile_id = :profileId and source_id = :sourceId")
    suspend fun get(profileId: UUID, sourceId: UUID): FeedSubscription?

    @Query(
        "insert into feeds.feed_subscriptions (profile_id, source_id) values (:profileId, :sourceId) " +
            "on conflict (profile_id, source_id) do nothing"
    )
    suspend fun add(profileId: UUID, sourceId: UUID)

    @Query("delete from feeds.feed_subscriptions where profile_id = :profileId and source_id = :sourceId")
    suspend fun delete(profileId: UUID, sourceId: UUID)

    /** The live feed sources a profile is subscribed to, joined through to `feed_sources`, newest-first. */
    @Query(
        "select fs.* from feeds.feed_subscriptions sub " +
            "join feeds.feed_sources fs on fs.source_id = sub.source_id and fs.deleted is null " +
            "where sub.profile_id = :profileId order by sub.created desc offset :offset limit :limit"
    )
    suspend fun getSubscribedSources(profileId: UUID, offset: Int, limit: Int): List<FeedSource>
}
