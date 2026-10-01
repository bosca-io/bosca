package bosca.recommendations.service

import bosca.recommendations.model.Recommendation
import bosca.recommendations.model.RecommendationSource
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Retrieves content recommendations for user profiles and manages
 * user dismissals. Combines pre-computed recommendations from batch
 * strategies with real-time similarity results, applies deduplication
 * and dismissed-item filtering, then returns ranked results.
 *
 * User engagement signals (views, clicks, completions) are tracked
 * through the existing analytics event pipeline rather than a separate
 * interaction tracking system. Recommendation attribution is passed
 * via the analytics event's Element.extras field.
 */
interface RecommendationService : Service {

    /**
     * Computes source-group attribution in batches against each result's captured model and inputs.
     * Called only when GraphQL selects sources; unavailable attribution returns an empty list per result.
     */
    suspend fun getSources(recommendations: List<Recommendation>): List<List<RecommendationSource>>

    /**
     * Retrieves the profile's personalized content feed (the `forYou` surface) from the trained
     * [bosca.recommendations.model.RecommendationStrategyType.PERSONALIZED] model — retrieved and ranked
     * for this viewer, with dismissed items filtered out. Falls back to trending when the model has no
     * usable result for this source-less request.
     *
     * @param profileId the UUID of the profile to get recommendations for
     * @param offset the number of recommendations to skip for pagination
     * @param limit the maximum number of recommendations to return
     * @param mlEnabled whether the learned ranker may order the candidates. The default (true) is the
     *        production path; callers running an A/B test pass false for the heuristic arm so the
     *        ML ranker is bypassed and the heuristic assembler decides the order.
     * @param modelVersion pins the ML ranker to a specific TF Serving model version. Null serves the
     *        latest. Used by the model-vs-model online A/B to route each arm to a different version.
     * @param contextType the cached/model candidate context to select; null selects `default`
     * @param languageTag the content language to return; null uses the profile's preferred locale,
     *        falling back to [DEFAULT_LANGUAGE_TAG]
     * @return the ranked list of [Recommendation] instances
     */
    suspend fun getForProfile(
        profileId: UUID,
        offset: Long,
        limit: Int,
        mlEnabled: Boolean = true,
        modelVersion: Long? = null,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<Recommendation>

    /**
     * Retrieves content recommendations for a specific placement by combining its linked strategy
     * sources. Materialized strategies read their global candidate pools; an active personalized strategy
     * is served live for [profileId]. Scores are normalized per source before blending, then profile
     * dismissals and placement output rules are applied. Model results already include their rating
     * influence. Anonymous requests skip personalized strategies.
     *
     * @param profileId the UUID of the profile, or null for anonymous users
     * @param placementSlug the slug identifying the display location
     * @param limit the maximum number of recommendations to return
     * @param contextType the cached/model candidate context to select; null selects `default`
     * @param languageTag the content language to return; null uses the profile's preferred locale when
     *        a profile is present, otherwise [DEFAULT_LANGUAGE_TAG]
     * @return the ranked list of [Recommendation] instances for the placement
     */
    suspend fun getForPlacement(
        profileId: UUID?,
        placementSlug: String,
        limit: Int,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<Recommendation>

    /**
     * Retrieves content items similar to a given metadata item using the captured content-similarity model.
     * Operational embedding chunks are consumed during model training and are never queried directly
     * to produce recommendation results.
     *
     * @param metadataId the UUID of the content to find similar items for
     * @param limit the maximum number of similar items to return
     * @param contextType the cached/model candidate context to select; null selects `default`
     * @param languageTag the content language to return; null uses [DEFAULT_LANGUAGE_TAG]
     * @return the list of [Recommendation] instances ranked by similarity
     */
    suspend fun getSimilar(
        metadataId: UUID,
        limit: Int,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<Recommendation>

    /**
     * Retrieves behavioral co-engagement matches for the source item. The selected personalized model
     * restricts its candidates to co-engagement edges and supplies final ranking scores, optionally using
     * the viewing profile. If unavailable or disabled, the materialized co-engagement pool supplies the
     * fallback. Live eligibility and dismissals are applied, with pagination to refill filtered results.
     *
     * @param metadataId the UUID of the source content item being viewed
     * @param profileId the viewing profile to personalize for, or null for the global set
     * @param limit the maximum number of co-engaged items to return
     * @param mlEnabled whether to use the personalized context export
     * @param modelVersion selects a completed model version from this context; null uses its active version
     * @param contextType the cached/model candidate context to select; null selects `default`
     * @param languageTag the content language to return; null uses the profile's preferred locale when
     *        a profile is present, otherwise [DEFAULT_LANGUAGE_TAG]
     * @return the list of [Recommendation] instances ranked by co-engagement strength (and viewer fit when personalized)
     */
    suspend fun getCoEngaged(
        metadataId: UUID,
        profileId: UUID?,
        limit: Int,
        mlEnabled: Boolean = true,
        modelVersion: Long? = null,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<Recommendation>

    /**
     * Retrieves the selected context model's final source-conditioned ranking. Similarity, editorial
     * preference and learned behavioral contributions are combined inside that model before top-K.
     * Kotlin preserves its scores and refills after live filtering. Without a usable personalized export,
     * the matching content model supplies similar items. An unknown source yields no results.
     *
     * @param metadataId the content item being viewed
     * @param profileId the viewing profile to personalize for, or null for population-level ranking
     * @param limit the maximum number of items to return
     * @param mlEnabled whether to use the personalized context export
     * @param modelVersion selects a completed model version from this context; null uses its active version
     * @param contextType the cached/model candidate context to select; null selects `default`
     * @param languageTag the content language to return; null uses the profile's preferred locale when
     *        a profile is present, otherwise [DEFAULT_LANGUAGE_TAG]
     * @return the model-ranked recommendations for this item's context
     */
    suspend fun getRecommended(
        metadataId: UUID,
        profileId: UUID?,
        limit: Int,
        mlEnabled: Boolean = true,
        modelVersion: Long? = null,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<Recommendation>

    /**
     * Retrieves globally trending content based on recent interaction
     * velocity across all users.
     *
     * @param offset the number of items to skip for pagination
     * @param limit the maximum number of trending items to return
     * @param contextType the cached/model candidate context to select; null selects `default`
     * @param languageTag the content language to return; null uses [DEFAULT_LANGUAGE_TAG]
     * @return the list of [Recommendation] instances ranked by trend score
     */
    suspend fun getTrending(
        offset: Long,
        limit: Int,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<Recommendation>

    /** Clears cached personalized feeds after changing the active model or ML strategy configuration. */
    suspend fun invalidatePersonalizedFeeds()

    companion object {
        const val DEFAULT_LANGUAGE_TAG = "en"
        /** Language reconciliation context used by recommendation training and serving. */
        const val LANGUAGE_RESOLUTION_CONTEXT_KEY = "recommendations"
    }

    /**
     * Dismisses a content item for a profile, preventing it from
     * appearing in future recommendations. This is a user preference
     * stored separately from analytics events.
     *
     * @param profileId the UUID of the profile
     * @param metadataId the UUID of the metadata to dismiss, or null
     * @param collectionId the UUID of the collection to dismiss, or null
     */
    suspend fun dismiss(profileId: UUID, metadataId: UUID?, collectionId: UUID?)

    /**
     * Undoes a previous dismissal, allowing the content item to appear
     * in recommendations again.
     *
     * @param profileId the UUID of the profile
     * @param metadataId the UUID of the metadata to un-dismiss, or null
     * @param collectionId the UUID of the collection to un-dismiss, or null
     */
    suspend fun undismiss(profileId: UUID, metadataId: UUID?, collectionId: UUID?)

    /**
     * Removes expired recommendations across all strategies,
     * clearing entries whose expiration timestamp has passed.
     *
     * @return the number of expired recommendations removed
     */
    suspend fun removeExpired(): Long
}
