package bosca.feeds.service

import bosca.feeds.model.FeedSource
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages a profile's subscriptions to feed sources. Subscriptions, together with a profile's
 * own user-owned sources, define the per-user feed.
 */
interface FeedSubscriptionService : Service {

    /** Subscribe [profileId] to feed source [sourceId] (idempotent); returns the subscribed source. */
    suspend fun subscribe(profileId: UUID, sourceId: UUID): FeedSource

    /** Remove [profileId]'s subscription to [sourceId]; false if there was no subscription. */
    suspend fun unsubscribe(profileId: UUID, sourceId: UUID): Boolean

    /** The feed sources [profileId] is subscribed to, newest-subscription-first, paged. */
    suspend fun getSubscribedSources(profileId: UUID, offset: Int, limit: Int): List<FeedSource>
}
