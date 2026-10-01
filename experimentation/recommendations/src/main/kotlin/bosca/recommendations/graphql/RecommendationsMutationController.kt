package bosca.recommendations.graphql

import bosca.content.metadata.service.MetadataService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.profile.rating.service.ProfileRatingService
import bosca.recommendations.model.RecommendationFeedback
import bosca.recommendations.service.RecommendationService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object RecommendationMutation

/**
 * Unified mutation entry point for the recommendation system. Provides user-facing
 * dismiss/undismiss + feedback operations alongside nested admin sub-controllers for strategies
 * and placements, all under a single `recommendation` mutation root.
 */
@TypeController
class RecommendationsMutationController(
    private val recommendationService: RecommendationService,
    private val ratingService: ProfileRatingService,
    private val profileService: ProfileService,
    private val groupEvaluator: GroupEvaluator,
    private val metadataService: MetadataService,
) : GraphQLController<RecommendationMutation> {

    @Field
    suspend fun dismiss(authentication: AuthenticationContext, profileId: UUID, metadataId: UUID?, collectionId: UUID?): Boolean {
        verifyProfileOwnership(authentication, profileId, profileService, groupEvaluator)
        recommendationService.dismiss(profileId, metadataId, collectionId)
        return true
    }

    @Field
    suspend fun undismiss(authentication: AuthenticationContext, profileId: UUID, metadataId: UUID?, collectionId: UUID?): Boolean {
        verifyProfileOwnership(authentication, profileId, profileService, groupEvaluator)
        recommendationService.undismiss(profileId, metadataId, collectionId)
        return true
    }

    /**
     * Record a user's feedback [gesture] on a content item, routed to the existing primitives:
     * Boost/Lower/Care upsert a profile rating (tuning the rating-aware re-rank); Hide dismisses the item
     * (already filtered out at serve time). The caller may only act on their own profile (or as an
     * admin). Provide either [metadataId] (+ optional [metadataVersion]) or [collectionId].
     * An omitted metadata version resolves to the item's current version before persisting feedback.
     */
    @Field
    suspend fun feedback(
        authentication: AuthenticationContext,
        profileId: UUID,
        gesture: RecommendationFeedback,
        metadataId: UUID?,
        metadataVersion: Int?,
        collectionId: UUID?,
    ): Boolean {
        verifyProfileOwnership(authentication, profileId, profileService, groupEvaluator)
        val rating = ratingFor(gesture)
        if (rating == null) {
            recommendationService.dismiss(profileId, metadataId, collectionId)
        } else {
            val version = metadataVersion ?: metadataId?.let {
                requireNotNull(metadataService.getById(it)) { "Metadata not found: $it" }.version
            }
            ratingService.updateRating(profileId, rating, metadataId, version, collectionId)
        }
        return true
    }

    @Field
    fun strategies() = RecommendationStrategiesMutation

    @Field
    fun placements() = RecommendationPlacementsMutation

    @Field
    fun contexts() = RecommendationContextsMutation

    @Field
    fun personalizationSignals() = PersonalizationSignalsMutation

    /**
     * Default gesture → 1–5 rating mapping (Hide is not a rating; it dismisses). This is tunable product
     * policy — adjust the values here to change how strongly each gesture weights the rating-aware
     * re-rank.
     */
    private fun ratingFor(gesture: RecommendationFeedback): Int? = when (gesture) {
        RecommendationFeedback.BOOST -> 5
        RecommendationFeedback.CARE -> 4
        RecommendationFeedback.LOWER -> 2
        RecommendationFeedback.HIDE -> null
    }
}
