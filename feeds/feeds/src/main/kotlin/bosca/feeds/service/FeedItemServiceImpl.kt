package bosca.feeds.service

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.feeds.repository.FeedItemRepository
import bosca.recommendations.service.RecommendationService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Resolves feed items to content `Metadata`:
 *  - a single source's items and a profile's chronological assembled feed across subscribed +
 *    owned sources, from the `feeds.feed_items` dedup mappings;
 *  - the profile's **personalized** ("For you") feed and the item-context **recommended** blend, delegated to the
 *    recommendations domain (which applies the rating-aware re-rank + care gate) — feeds owns the
 *    surface, recommendations owns the ranking.
 *
 * Mapping queries / recommendations establish the order, but `getByIds` is a set-oriented batch with no
 * order guarantee — so resolved records are re-sorted into the source order; ids with no surviving
 * Metadata are dropped.
 */
@ServiceImplementation
class FeedItemServiceImpl(
    private val feedItemRepository: FeedItemRepository,
    private val metadataService: MetadataService,
    private val recommendationService: RecommendationService,
) : FeedItemService {

    override suspend fun getBySource(sourceId: UUID, offset: Int, limit: Int): List<Metadata> =
        resolveOrdered(feedItemRepository.getBySource(sourceId, offset, limit).map { it.metadataId })

    override suspend fun getForProfile(profileId: UUID, offset: Int, limit: Int): List<Metadata> =
        resolveOrdered(feedItemRepository.getForProfile(profileId, offset, limit).map { it.metadataId })

    override suspend fun getForYou(profileId: UUID, offset: Int, limit: Int, contextType: String?): List<Metadata> =
        resolveOrdered(
            recommendationService.getForProfile(
                profileId,
                offset.toLong(),
                limit,
                mlEnabled = true,
                modelVersion = null,
                contextType = contextType,
            )
                .mapNotNull { it.metadataId },
        )

    override suspend fun getRecommended(
        metadataId: UUID,
        profileId: UUID,
        limit: Int,
        contextType: String?,
    ): List<Metadata> = resolveOrdered(
        recommendationService.getRecommended(
            metadataId,
            profileId,
            limit,
            mlEnabled = true,
            modelVersion = null,
            contextType = contextType,
        )
            .mapNotNull { it.metadataId },
    )

    /** Resolve mapping ids to Metadata, preserving the (relevance/newest-first) order the source returned. */
    private suspend fun resolveOrdered(ids: List<UUID>): List<Metadata> {
        if (ids.isEmpty()) return emptyList()
        val byId = metadataService.getByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }
}
