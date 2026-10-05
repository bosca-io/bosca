package bosca.feeds.service

import bosca.content.metadata.model.Metadata
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Reads ingested feed items as content `Metadata`. Resolves the feeds-owned
 * `(source, GUID) → Metadata` dedup mappings to the content records, newest-first.
 */
interface FeedItemService : Service {

    /** The content Metadata ingested for [sourceId], newest-first, paged. */
    suspend fun getBySource(sourceId: UUID, offset: Int, limit: Int): List<Metadata>

    /**
     * The content Metadata in [profileId]'s assembled feed — items from the sources the
     * profile is subscribed to or owns — newest-first, paged.
     */
    suspend fun getForProfile(profileId: UUID, offset: Int, limit: Int): List<Metadata>

    /**
     * The profile's **personalized** feed — the "For you" surface: the trained model's ranked
     * recommendations for this profile, care-gated by the recommendations domain, resolved to content
     * Metadata. Ranking is delegated, not done here. [contextType] selects a recommendation candidate context;
     * null selects `default`.
     */
    suspend fun getForYou(
        profileId: UUID,
        offset: Int,
        limit: Int,
        contextType: String? = null,
    ): List<Metadata>

    /**
     * The item-context **recommended** surface for [metadataId]: the recommendations domain's
     * merge of behavioral co-engagement and content similarity, re-ranked for [profileId], resolved to
     * content Metadata. Ranking is delegated, not done here. [contextType] selects a recommendation
     * candidate context; null selects `default`.
     */
    suspend fun getRecommended(
        metadataId: UUID,
        profileId: UUID,
        limit: Int,
        contextType: String? = null,
    ): List<Metadata>
}
