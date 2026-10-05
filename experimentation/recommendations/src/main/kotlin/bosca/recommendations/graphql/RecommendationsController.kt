package bosca.recommendations.graphql

import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.experimentation.service.FeatureFlagService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.recommendations.model.Recommendation as RecommendationModel
import bosca.recommendations.service.RecommendationService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

/**
 * Unified query entry point for the recommendation system. Nests personalized
 * content feeds, semantic similarity, trending content, and admin sub-controllers
 * for strategies and placements under a single `recommendation` root field.
 */
object RecommendationQuery

@TypeController(type = "Recommendation")
class RecommendationsController(
    private val recommendationService: RecommendationService,
    private val profileService: ProfileService,
    private val groupEvaluator: GroupEvaluator,
    private val featureFlagService: FeatureFlagService,
    private val metadataService: MetadataService,
    private val collectionService: CollectionService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
) : GraphQLController<RecommendationQuery> {

    @Field
    suspend fun forYou(
        authentication: AuthenticationContext,
        call: ServerCall,
        profileId: UUID?,
        offset: Long,
        limit: Int,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<RecommendationModel> {
        // Defaults to the caller's own profile; passing an explicit profileId is permitted only for its
        // owner or an admin/service account — resolveProfileId → verifyProfileOwnership enforces that.
        val resolvedProfileId = resolveProfileId(authentication, profileId) ?: return emptyList()
        val engine = resolveEngine(authentication, call)
        val safeOffset = maxOf(offset, 0)
        val safeLimit = limit.coerceIn(1, 100)
        return filterAllowed(
            authentication,
            recommendationService.getForProfile(
                resolvedProfileId,
                safeOffset,
                safeLimit,
                engine.mlEnabled,
                engine.modelVersion,
                contextType,
                languageTag,
            ),
        )
    }

    /** The recommendation engine + model version selected for a request by the A/B flags. */
    private data class EngineSelection(val mlEnabled: Boolean, val modelVersion: Long?)

    /**
     * Resolves the A/B arms for this request from two feature flags using the standard
     * `X-Installation-ID` request header (each evaluation buckets the installation and records its
     * current assignment). A request without that transport identity does not participate in an
     * experiment and uses the production recommendation defaults.
     *  - `recommendation-engine` (ml/heuristic): the heuristic arm bypasses the learned ranker, so an
     *    experiment can measure ML's engagement lift over the heuristic.
     *  - `recommendation-model` (model-vs-model): each variation's value is a TF Serving model version,
     *    so an experiment can pit a champion model version against a challenger online.
     * Any flag that's absent, disabled, or errors degrades to the production default (ML, latest version).
     */
    private suspend fun resolveEngine(
        authentication: AuthenticationContext,
        call: ServerCall,
    ): EngineSelection {
        val principalId = authentication.principal()?.id ?: return EngineSelection(mlEnabled = true, modelVersion = null)
        val installationId = call.request.header(INSTALLATION_ID_HEADER)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return EngineSelection(mlEnabled = true, modelVersion = null)
        val mlEnabled = try {
            featureFlagService.evaluate(RECOMMENDATION_ENGINE_FLAG, principalId, installationId, null).variationKey != HEURISTIC_ARM
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("recommendation-engine flag unavailable ({}); serving ML path", e.message)
            true
        }
        if (!mlEnabled) return EngineSelection(mlEnabled = false, modelVersion = null)
        val modelVersion = try {
            val evaluation = featureFlagService.evaluate(RECOMMENDATION_MODEL_FLAG, principalId, installationId, null)
            // Only pin a version when this is a live experiment assignment; a disabled flag falls back to
            // its default variation, which we must NOT treat as a pin (serve the latest version instead).
            if (evaluation.experimentId != null) (evaluation.value as? JsonPrimitive)?.longOrNull else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("recommendation-model flag unavailable ({}); serving the latest model version", e.message)
            null
        }
        return EngineSelection(mlEnabled = true, modelVersion = modelVersion)
    }

    /**
     * The profile to personalize a request for. Uses the explicit [profileId] when given (verifying the
     * caller owns it); otherwise defaults to the **authenticated user's primary profile**, so a signed-in
     * user gets personalized results without having to pass their own id. Null when neither applies
     * (anonymous request, no explicit id) — the global, unpersonalized path.
     */
    private suspend fun resolveProfileId(authentication: AuthenticationContext?, profileId: UUID?): UUID? {
        val identity = authentication?.principal() ?: return null
        val principal = identity.asPrincipal()
        if (profileId != null) {
            verifyProfileOwnership(authentication, profileId, profileService, groupEvaluator)
            return profileId
        }
        return profileService.getPrimaryProfile(principal)?.id
    }

    @Field
    suspend fun placement(
        authentication: AuthenticationContext?,
        profileId: UUID?,
        placementSlug: String,
        limit: Int,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<RecommendationModel> {
        val resolvedProfileId = resolveProfileId(authentication, profileId)
        val safeLimit = limit.coerceIn(1, 50)
        return filterAllowed(
            authentication,
            recommendationService.getForPlacement(resolvedProfileId, placementSlug, safeLimit, contextType, languageTag),
        )
    }

    @Field
    suspend fun similar(
        authentication: AuthenticationContext?,
        metadataId: UUID,
        limit: Int,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<RecommendationModel> {
        val safeLimit = limit.coerceIn(1, 50)
        return filterAllowed(
            authentication,
            recommendationService.getSimilar(metadataId, safeLimit, contextType, languageTag),
        )
    }

    @Field
    suspend fun coEngaged(
        authentication: AuthenticationContext?,
        call: ServerCall,
        metadataId: UUID,
        profileId: UUID?,
        limit: Int,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<RecommendationModel> {
        // Defaults to the signed-in user's profile when profileId is omitted (see resolveProfileId).
        val resolvedProfileId = resolveProfileId(authentication, profileId)
        // Only resolve the A/B arm for personalized requests — an anonymous/global co-engagement set ignores
        // the ML parameters, so we skip flag evaluation to avoid recording spurious experiment assignments.
        val engine = if (resolvedProfileId != null && authentication != null) {
            resolveEngine(authentication, call)
        } else {
            EngineSelection(mlEnabled = true, modelVersion = null)
        }
        val safeLimit = limit.coerceIn(1, 50)
        return filterAllowed(
            authentication,
            recommendationService.getCoEngaged(
                metadataId,
                resolvedProfileId,
                safeLimit,
                engine.mlEnabled,
                engine.modelVersion,
                contextType,
                languageTag,
            ),
        )
    }

    @Field
    suspend fun recommended(
        authentication: AuthenticationContext?,
        call: ServerCall,
        metadataId: UUID,
        profileId: UUID?,
        limit: Int,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<RecommendationModel> {
        // Defaults to the signed-in user's profile when profileId is omitted (see resolveProfileId).
        val resolvedProfileId = resolveProfileId(authentication, profileId)
        // Same A/B handling as `coEngaged`: only resolve the arm for personalized requests, so a global merge
        // doesn't record a spurious experiment assignment.
        val engine = if (resolvedProfileId != null && authentication != null) {
            resolveEngine(authentication, call)
        } else {
            EngineSelection(mlEnabled = true, modelVersion = null)
        }
        val safeLimit = limit.coerceIn(1, 50)
        return filterAllowed(
            authentication,
            recommendationService.getRecommended(
                metadataId,
                resolvedProfileId,
                safeLimit,
                engine.mlEnabled,
                engine.modelVersion,
                contextType,
                languageTag,
            ),
        )
    }

    @Field
    suspend fun trending(
        authentication: AuthenticationContext?,
        offset: Long,
        limit: Int,
        contextType: String? = null,
        languageTag: String? = null,
    ): List<RecommendationModel> {
        val safeOffset = maxOf(offset, 0)
        val safeLimit = limit.coerceIn(1, 100)
        return filterAllowed(
            authentication,
            recommendationService.getTrending(safeOffset, safeLimit, contextType, languageTag),
        )
    }

    /** Removes recommendation candidates the caller cannot view before nested fields are resolved. */
    private suspend fun filterAllowed(
        authentication: AuthenticationContext?,
        recommendations: List<RecommendationModel>,
    ): List<RecommendationModel> {
        if (recommendations.isEmpty()) return recommendations

        val metadataIds = recommendations.mapNotNull { it.metadataId }.distinct()
        val allowedMetadataIds = metadataIds.takeIf { it.isNotEmpty() }?.let {
            metadataPermissionEvaluator
                .filterAllowed(authentication, metadataService.getByIds(it), PermissionAction.VIEW)
                .map { it.id }
                .toSet()
        }.orEmpty()

        val collectionIds = recommendations.mapNotNull { it.collectionId }.distinct()
        val allowedCollectionIds = collectionIds.takeIf { it.isNotEmpty() }?.let {
            collectionPermissionEvaluator
                .filterAllowed(authentication, collectionService.getByIds(it), PermissionAction.VIEW)
                .map { it.id }
                .toSet()
        }.orEmpty()

        return recommendations.filter { recommendation ->
            recommendation.metadataId?.let { it in allowedMetadataIds }
                ?: recommendation.collectionId?.let { it in allowedCollectionIds }
                ?: false
        }
    }

    @Field
    fun strategies() = RecommendationStrategies

    @Field
    fun placements() = RecommendationPlacements

    @Field
    fun contexts() = RecommendationContexts

    @Field
    fun modelSelection() = RecommendationModelSelection

    @Field
    fun personalizationSignals() = PersonalizationSignals

    companion object {
        private val log = LoggerFactory.getLogger(RecommendationsController::class.java)

        /** Feature flag whose variations split traffic between the ML and heuristic recommendation engines. */
        const val RECOMMENDATION_ENGINE_FLAG = "recommendation-engine"

        /** Standard installation identity supplied by Bosca GraphQL clients. */
        private const val INSTALLATION_ID_HEADER = "X-Installation-ID"

        /** Variation key for the control arm — heuristic assembler only, learned ranker bypassed. */
        const val HEURISTIC_ARM = "heuristic"

        /** Feature flag whose variation values are TF Serving model versions — the model-vs-model A/B. */
        const val RECOMMENDATION_MODEL_FLAG = "recommendation-model"
    }
}
