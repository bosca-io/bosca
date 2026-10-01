package bosca.feeds.service

import bosca.db.transaction
import bosca.feeds.model.FeedSource
import bosca.feeds.repository.FeedSubscriptionRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Subscriptions to feed sources. Subscribing is idempotent (the repository upserts with
 * `on conflict do nothing`) and validates the source exists; unsubscribing reports whether a
 * subscription was actually present. Reads resolve straight through to the subscribed [FeedSource]s.
 */
@ServiceImplementation
class FeedSubscriptionServiceImpl(
    private val repository: FeedSubscriptionRepository,
    private val feedSourceService: FeedSourceService,
) : FeedSubscriptionService {

    override suspend fun subscribe(profileId: UUID, sourceId: UUID): FeedSource = transaction {
        val source = feedSourceService.get(sourceId) ?: error("feed source $sourceId not found")
        repository.add(profileId, sourceId)
        source
    }

    override suspend fun unsubscribe(profileId: UUID, sourceId: UUID): Boolean = transaction {
        repository.get(profileId, sourceId) ?: return@transaction false
        repository.delete(profileId, sourceId)
        true
    }

    override suspend fun getSubscribedSources(profileId: UUID, offset: Int, limit: Int): List<FeedSource> =
        repository.getSubscribedSources(profileId, offset, limit)
}
