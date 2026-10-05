package bosca.recommendations.service

import bosca.cache.ServiceCache
import bosca.content.metadata.service.MetadataService
import bosca.languages.model.LanguageTagResolution
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import bosca.languages.service.LanguagesService
import bosca.recommendations.cache.RecommendationFeedCacheKeyId
import bosca.recommendations.cache.RecommendationFeedCacheKeySerializer
import bosca.recommendations.model.Recommendation
import bosca.recommendations.model.RecommendationContext
import bosca.recommendations.model.RecommendationContextModel
import bosca.recommendations.model.RecommendationDismissal
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.model.RecommendationStrategyStatus
import bosca.recommendations.model.RecommendationStrategyType
import bosca.recommendations.model.RecommendationSource
import bosca.recommendations.model.RecommendationInference
import bosca.recommendations.model.RecommendationTrainingStatus
import bosca.recommendations.model.CoEngagement
import bosca.recommendations.ml.TfServingClient
import bosca.recommendations.ml.TfServingConfiguration
import bosca.recommendations.repository.RecommendationDismissalRepository
import bosca.recommendations.repository.RecommendationPlacementRepository
import bosca.recommendations.repository.RecommendationPlacementStrategyRepository
import bosca.recommendations.repository.RecommendationRepository
import bosca.recommendations.repository.RecommendationStrategyRepository
import bosca.recommendations.repository.CoEngagementRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.profile.rating.service.ProfileRatingService
import bosca.recommendations.model.RecommendationPlacement
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.minutes
import org.slf4j.LoggerFactory

/**
 * Serves final rankings from the selected context model, applies live eligibility and dismissals,
 * and refills from the same model. Existing strategy pools supply trending and collection placements.
 */
@ServiceImplementation
class RecommendationServiceImpl(
    private val recommendationRepository: RecommendationRepository,
    private val dismissalRepository: RecommendationDismissalRepository,
    private val placementRepository: RecommendationPlacementRepository,
    private val placementStrategyRepository: RecommendationPlacementStrategyRepository,
    private val strategyRepository: RecommendationStrategyRepository,
    private val coEngagementRepository: CoEngagementRepository,
    private val metadataService: MetadataService,
    private val profileService: ProfileService,
    private val ratingService: ProfileRatingService,
    private val contextService: RecommendationContextService,
    private val languagesService: LanguagesService,
    private val json: Json,
    private val tfServingConfiguration: TfServingConfiguration,
) : RecommendationService {

    private data class RecommendationLanguage(
        /** The concrete base/variant language whose recommendability flag applies. */
        val sourceLanguageTag: String,
        /** The canonical language facet baked into the recommendation model and SQL candidate pools. */
        val resolvedLanguageTag: String,
    )

    /** One immutable model selection for the complete recommendation request. */
    private data class ServingContext(val saved: RecommendationContext, val model: RecommendationContextModel?) {
        val type: String get() = saved.type
        val modelType: String get() = model?.let { it.context.type } ?: type
        val version: Long? get() = model?.version
        private val metadataFilter = model?.let { it.context.contentFilter.metadata.normalized() }
        val includedContentTypePrefixes = metadataFilter?.includedContentTypePrefixes.orEmpty()
        val excludedContentTypePrefixes = metadataFilter?.excludedContentTypePrefixes.orEmpty()
        val includedAttributeTypes = metadataFilter?.includedAttributeTypes.orEmpty()
        val excludedAttributeTypes = metadataFilter?.excludedAttributeTypes.orEmpty()

    }

    private fun configuration(model: RecommendationContextModel): TfServingConfiguration =
        tfServingConfiguration.copy(
            modelName = model.personalizedModelName,
            contentModelName = model.contentModelName,
            modelVersion = model.version,
            contentModelVersion = model.version,
        )

    private val assembler = RecommendationAssembler(metadataService)

    override suspend fun getSources(recommendations: List<Recommendation>): List<List<RecommendationSource>> {
        val result = recommendations.map { recommendation ->
            if (recommendation.inference == null && RecommendationSource.TRENDING in recommendation.sources) {
                listOf(RecommendationSource.TRENDING)
            } else emptyList()
        }.toMutableList()
        for ((input, indexed) in recommendations.withIndex().filter { it.value.inference != null && it.value.metadataId != null }
            .groupBy { requireNotNull(it.value.inference) }) {
            try {
                val model = contextService.getModel(input.modelVersion) ?: continue
                if (model.status != RecommendationTrainingStatus.COMPLETED) continue
                val client = tfClient(configuration(model))
                val sources = client.explain(input, indexed.map { requireNotNull(it.value.metadataId).toString() })
                indexed.forEachIndexed { index, recommendation -> result[recommendation.index] = sources[index] }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                log.warn("Recommendation attribution unavailable for model {}", input.modelVersion, exception)
            }
        }
        return result
    }

    /** Reads model-ranked pages until live filtering has supplied enough items or the catalog ends. */
    private suspend fun modelRecommendations(
        context: ServingContext,
        languageTag: String,
        limit: Int,
        profileId: UUID? = null,
        sourceId: UUID? = null,
        personalized: Boolean,
        coEngagedOnly: Boolean = false,
        dismissed: Set<UUID> = emptySet(),
        strategyId: UUID = UUID.NIL,
        refill: Boolean = true,
    ): List<Recommendation>? {
        if (limit <= 0) return emptyList()
        val model = context.model ?: return null
        val input = RecommendationInference(model.version, personalized, context.modelType, languageTag, profileId, sourceId)
        val client = tfClient(configuration(model))
        val seen = HashSet<String>()
        val result = ArrayList<Recommendation>()
        var offset = 0
        try {
            while (result.size < limit) {
                val predictions = client.predictPage(input, offset, coEngagedOnly)
                if (predictions.isEmpty()) break
                offset += predictions.size
                val unseen = predictions.filter { seen.add(it.contentId) }
                if (unseen.isEmpty()) {
                    log.warn("Model {} repeated a recommendation page at offset {}", model.version, offset)
                    break
                }
                val candidates = unseen.mapNotNull { prediction ->
                    val id = runCatching { UUID.parse(prediction.contentId) }.getOrNull() ?: return@mapNotNull null
                    if (id == sourceId || id in dismissed || !prediction.score.isFinite()) return@mapNotNull null
                    Recommendation(
                        metadataId = id, strategyId = strategyId, score = prediction.score,
                        reason = if (personalized) "Recommended by context model" else "Similar by content model",
                        inference = input,
                    )
                }
                result += filterEligible(candidates, context, RecommendationLanguage(languageTag, languageTag))
                if (!refill && result.isNotEmpty()) break
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            log.warn("Recommendation model {} unavailable at offset {}", model.version, offset, exception)
            if (result.isEmpty()) return null
        }
        return result.take(limit)
    }

    // This service owns one underlying OkHttp resource pool. TfServingClient derives a lightweight client
    // that shares those resources while applying the timeout from the selected model configuration.
    private val tfServingHttpClient = OkHttpClient()

    private fun tfClient(config: TfServingConfiguration) =
        TfServingClient(config, tfServingHttpClient, json)

    // A profile's feed is computed live from the model on demand (per request) — NOT precomputed for every
    // profile in a scheduled batch — and cached here by profile, context, exact content representation, and
    // resolved model language with a short TTL.
    // Only profiles that actually request a feed pay the inference cost, so this scales with active users
    // rather than total users. Invalidated on dismiss/undismiss; a newly selected model has its own key.
    // Caches the default ML arm only (see [getForProfile]).
    private val feedCache = ServiceCache("recommendations:feed", RecommendationFeedCacheKeySerializer, expiration = FEED_CACHE_TTL) { key ->
        val saved = contextService.getByType(requireNotNull(key.contextType))
            ?: return@ServiceCache emptyList<Recommendation>()
        val model = key.modelVersion?.let { version ->
            requireNotNull(contextService.getModel(version)) { "Recommendation model version not found: $version" }
                .also {
                    require(it.contextId == saved.id && it.status == RecommendationTrainingStatus.COMPLETED) {
                        "Recommendation model version does not belong to this context or is not completed"
                    }
                }
        }
        computePersonalizedFeed(
            key.profileId,
            mlEnabled = true,
            candidateLimit = FEED_CANDIDATE_CACHE_SIZE,
            context = ServingContext(saved, model),
            language = RecommendationLanguage(
                requireNotNull(key.sourceLanguageTag),
                requireNotNull(key.resolvedLanguageTag),
            ),
        )
    }

    override suspend fun getForProfile(
        profileId: UUID,
        offset: Long,
        limit: Int,
        mlEnabled: Boolean,
        modelVersion: Long?,
        contextType: String?,
        languageTag: String?,
    ): List<Recommendation> {
        val context = resolveContext(contextType, modelVersion)
        val resolvedLanguage = resolveLanguageTag(profileId, languageTag)
        val requestedEnd = (
            offset.coerceIn(0, FEED_CANDIDATE_CACHE_SIZE.toLong()) + limit.coerceAtLeast(0)
        ).coerceAtMost(FEED_CANDIDATE_CACHE_SIZE.toLong()).toInt()
        // Live, per-request serving: compute only the requesting profile's feed. The default arm is cached
        // per profile/context/language with a short TTL; the A/B arms (heuristic, or a pinned model version)
        // compute live and uncached so an experiment never reads the default arm's cached result.
        val feed = if (mlEnabled && modelVersion == null) {
            feedCache.get(
                RecommendationFeedCacheKeyId(
                    profileId = profileId,
                    contextType = context.type,
                    modelVersion = context.version,
                    sourceLanguageTag = resolvedLanguage.sourceLanguageTag,
                    resolvedLanguageTag = resolvedLanguage.resolvedLanguageTag,
                ),
            ) ?: emptyList()
        } else {
            computePersonalizedFeed(
                profileId,
                mlEnabled,
                requestedEnd,
                context,
                resolvedLanguage,
            )
        }
        var eligible = filterEligible(feed, context, resolvedLanguage)
        if (eligible.size < requestedEnd && eligible.none { it.isFallback() } && mlEnabled && context.model?.personalized == true) {
            val dismissed = dismissalRepository.getDismissedMetadataIds(profileId).toSet()
            modelRecommendations(context, resolvedLanguage.resolvedLanguageTag, requestedEnd,
                profileId = profileId, personalized = true, dismissed = dismissed)?.let {
                eligible = (it + eligible).mergeSources().take(requestedEnd)
            }
        }
        val reconciled = if (eligible.size < requestedEnd && eligible.none { it.isFallback() }) {
            supplementWithColdStart(
                profileId,
                eligible,
                requestedEnd,
                context,
                resolvedLanguage,
                dismissalRepository.getDismissedMetadataIds(profileId).toSet(),
                dismissalRepository.getDismissedCollectionIds(profileId).toSet(),
            )
        } else {
            eligible
        }
        return reconciled.asSequence()
            .drop(offset.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            .take(limit)
            .toList()
            .withRequestContext(context)
    }

    /** Adds provenance after cache reads; fetching recommendations does not itself record an exposure. */
    private fun List<Recommendation>.withRequestContext(selected: ServingContext, sourceId: UUID? = null): List<Recommendation> {
        if (isEmpty()) return this
        val requestId = UUID.random().toString()
        return map { recommendation ->
            val existing = recommendation.context
            // Preserve arbitrary legacy strategy payloads; only object contexts can carry these extra keys.
            if (existing != null && existing !is JsonObject) return@map recommendation
            recommendation.copy(context = buildJsonObject {
                existing?.forEach { (key, value) ->
                    if (key !in REQUEST_CONTEXT_KEYS) put(key, value)
                }
                put("recommendation_context", selected.modelType)
                selected.version?.let { put("recommendation_model_version", it) }
                sourceId?.let { put("recommendation_source_id", it.toString()) }
                put("recommendation_request_id", requestId)
            })
        }
    }

    /**
     * Computes a profile's final ranked candidate feed (up to [FEED_CANDIDATE_CACHE_SIZE]) from its selected
     * context model in one inference call; if that's empty (cold/out-of-vocab user) or this
     * is the heuristic arm, fall back to clearly marked trending results.
     */
    private suspend fun computePersonalizedFeed(
        profileId: UUID,
        mlEnabled: Boolean,
        candidateLimit: Int,
        context: ServingContext,
        language: RecommendationLanguage,
    ): List<Recommendation> {
        val dismissedMetadataIds = dismissalRepository.getDismissedMetadataIds(profileId).toSet()
        val dismissedCollectionIds = dismissalRepository.getDismissedCollectionIds(profileId).toSet()
        if (mlEnabled) {
            liveRetrieveRank(
                profileId,
                candidateLimit,
                dismissedMetadataIds,
                context,
                language.resolvedLanguageTag,
            )?.let {
                val eligible = filterEligible(it, context, language)
                // Preserve the model's own bounded result in the cache. Request-level reconciliation below
                // supplements only as far as the requested page, instead of turning every cache miss into a
                // 500-candidate cold-start computation.
                if (eligible.isNotEmpty()) return eligible
            }
        }
        return coldStartFallback(
            profileId,
            candidateLimit,
            dismissedMetadataIds,
            dismissedCollectionIds,
            context,
            language,
        )
    }

    /**
     * Fills an eligible model/cache result with live SQL-backed candidates. This is the serving-side handoff
     * for language-mapping edits: stale model candidates are removed immediately, while newly mapped content
     * can enter through the fallback until the next language-partitioned model is published. Called only when
     * [existing] contains fewer than [targetSize] candidates.
     */
    private suspend fun supplementWithColdStart(
        profileId: UUID,
        existing: List<Recommendation>,
        targetSize: Int,
        context: ServingContext,
        language: RecommendationLanguage,
        dismissedMetadataIds: Set<UUID>,
        dismissedCollectionIds: Set<UUID>,
    ): List<Recommendation> {
        val fallback = coldStartFallback(
            profileId,
            targetSize,
            dismissedMetadataIds,
            dismissedCollectionIds,
            context,
            language,
        )
        return (existing + fallback)
            .mergeSources()
            .take(targetSize)
    }

    /**
     * Serves the final scores from the selected context model, which ranks its eligible catalog before top-K.
     * Returns null — so the
     * caller drops to the cold-start fallback — when there is no active ML model, it's unreachable, or the
     * user is out-of-vocab (retrieval returns blanks, which the client drops).
     */
    private suspend fun liveRetrieveRank(
        profileId: UUID,
        limit: Int,
        dismissedMetadataIds: Set<UUID>,
        context: ServingContext,
        languageTag: String,
        linkedStrategy: RecommendationStrategy? = null,
    ): List<Recommendation>? {
        if (context.model?.personalized != true) return null
        return modelRecommendations(
            context, languageTag, limit, profileId = profileId, personalized = true,
            dismissed = dismissedMetadataIds, strategyId = linkedStrategy?.id ?: UUID.NIL,
            refill = false,
        )
    }

    /**
     * A source-less cold-start request falls back to trending. Every result is marked [asFallback] so callers
     * (and `RecommendationResult.fallback`) can distinguish it from an actual personalized recommendation.
     */
    private suspend fun coldStartFallback(
        profileId: UUID,
        limit: Int,
        dismissedMetadataIds: Set<UUID>,
        dismissedCollectionIds: Set<UUID>,
        context: ServingContext,
        language: RecommendationLanguage,
    ): List<Recommendation> {
        if (limit <= 0) return emptyList()
        val result = ArrayList<Recommendation>()
        var offset = 0L
        while (result.size < limit) {
            val page = trendingCandidates(context, language, offset, limit)
            if (page.isEmpty()) break
            offset += page.size
            result += page.filter {
                (it.metadataId == null || it.metadataId !in dismissedMetadataIds) &&
                    (it.collectionId == null || it.collectionId !in dismissedCollectionIds)
            }.map { it.asFallback(reason = "Trending — no personalized recommendations yet") }
        }
        return result.mergeSources().take(limit)
    }

    /** Resolves the request context, failing clearly instead of silently querying the wrong candidate facet. */
    private suspend fun resolveContext(contextType: String?, modelVersion: Long? = null): ServingContext {
        val type = contextType ?: RecommendationContext.DEFAULT_TYPE
        val saved = contextService.getByType(type)
            ?: throw NoSuchElementException("Recommendation context not found: $type")
        val version = modelVersion ?: saved.activeModelVersion
        val model = version?.let { selected ->
            val snapshot = contextService.getModel(selected)
                ?: throw IllegalArgumentException("Recommendation model version not found: $selected")
            require(snapshot.contextId == saved.id && snapshot.status == RecommendationTrainingStatus.COMPLETED) {
                "Recommendation model version does not belong to this context or is not completed"
            }
            snapshot
        }
        return ServingContext(saved, model)
    }

    private suspend fun resolveLanguageTag(languageTag: String?): RecommendationLanguage =
        languagesService.resolveLanguageTag(RecommendationService.LANGUAGE_RESOLUTION_CONTEXT_KEY, languageTag)
            .toRecommendationLanguage()

    private suspend fun resolveLanguageTag(profileId: UUID?, languageTag: String?): RecommendationLanguage {
        val requestedLanguageTag = if (languageTag != null || profileId == null) {
            languageTag
        } else {
            profileService.getAttributes(profileId)
                .getAttributeString(PROFILE_LOCALE_ATTRIBUTE_TYPE, PROFILE_LOCALE_ATTRIBUTE_FIELD)
        }
        return resolveLanguageTag(requestedLanguageTag)
    }

    private fun LanguageTagResolution.toRecommendationLanguage(): RecommendationLanguage = RecommendationLanguage(
        sourceLanguageTag = normalizedLanguageTag?.takeUnless { usedFallback } ?: resolvedLanguageTag,
        resolvedLanguageTag = resolvedLanguageTag,
    )

    override suspend fun getForPlacement(
        profileId: UUID?,
        placementSlug: String,
        limit: Int,
        contextType: String?,
        languageTag: String?,
    ): List<Recommendation> {
        val context = resolveContext(contextType)
        val resolvedLanguage = resolveLanguageTag(profileId, languageTag)
        val placement = placementRepository.getBySlug(placementSlug) ?: return emptyList()
        val links = placementStrategyRepository.getByPlacementId(placement.id)
        if (links.isEmpty()) return emptyList()
        val strategiesById = strategyRepository.getByIds(links.map { it.strategyId }).associateBy { it.id }
        val effectiveLimit = minOf(limit, placement.maxItems)
        val fetchLimit = effectiveLimit * ASSEMBLER_FETCH_MULTIPLIER
        val dismissedMetadataIds = if (profileId == null) {
            emptySet()
        } else {
            dismissalRepository.getDismissedMetadataIds(profileId).toSet()
        }
        val sources = links.map { link ->
            val strategy = strategiesById.getValue(link.strategyId)
            val candidates = when (strategy.type) {
                RecommendationStrategyType.PERSONALIZED -> {
                    if (profileId == null || strategy.status != RecommendationStrategyStatus.ACTIVE) {
                        emptyList()
                    } else {
                        liveRetrieveRank(
                            profileId,
                            fetchLimit,
                            dismissedMetadataIds,
                            context = context,
                            languageTag = resolvedLanguage.resolvedLanguageTag,
                            linkedStrategy = strategy,
                        )?.let { filterEligible(it, context, resolvedLanguage) }.orEmpty()
                    }
                }
                else -> recommendationRepository.getByStrategyId(
                    strategy.id,
                    context.type,
                    0,
                    fetchLimit,
                    resolvedLanguage.resolvedLanguageTag,
                    resolvedLanguage.sourceLanguageTag,
                    metadataEnabled = context.model != null,
                    includedContentTypePrefixes = context.includedContentTypePrefixes,
                    excludedContentTypePrefixes = context.excludedContentTypePrefixes,
                    includedAttributeTypes = context.includedAttributeTypes,
                    excludedAttributeTypes = context.excludedAttributeTypes,
                ).map { it.withSource(strategy.type.toSource()) }
            }
            candidates
        }.filter { it.isNotEmpty() }
        // Strategy scores are source-specific (for example, interaction counts for Trending versus model
        // relevance for PERSONALIZED). Normalize each contributing source before blending so one source's
        // numeric scale cannot suppress another source entirely. Link priority remains the stable tie-break.
        val candidates = if (sources.size <= 1) sources.flatten() else sources.flatMap(::normalizeScores)
        if (candidates.isEmpty()) return emptyList()
        // Anonymous: no personalization signal, so just the highest-scoring candidates.
        if (profileId == null) {
            return candidates.sortedByDescending { it.score }.take(effectiveLimit).withRequestContext(context)
        }
        // Apply placement output rules and dismissals; model results already include rating influence.
        val dismissedCollectionIds = dismissalRepository.getDismissedCollectionIds(profileId).toSet()
        val ratings = ratingService.getRatingsByProfile(profileId)
        return assembler.assemble(
            candidates, dismissedMetadataIds, dismissedCollectionIds, effectiveLimit,
            ratings = ratings, careFloor = careFloorOf(placement),
        ).withRequestContext(context)
    }

    /** The care-gate score floor for a placement, from its free-form `configuration` JSON (default 0 = off). */
    private fun careFloorOf(placement: RecommendationPlacement): Double =
        (placement.configuration as? JsonObject)?.get("careFloor")
            ?.let { (it as? JsonPrimitive)?.doubleOrNull } ?: 0.0

    override suspend fun getSimilar(
        metadataId: UUID,
        limit: Int,
        contextType: String?,
        languageTag: String?,
    ): List<Recommendation> {
        val context = resolveContext(contextType)
        val resolvedLanguage = resolveLanguageTag(languageTag)
        val similar = contentSimilar(
            metadataId,
            limit,
            context,
            resolvedLanguage.resolvedLanguageTag,
        ).orEmpty()
        return filterEligible(similar, context, resolvedLanguage).take(limit).withRequestContext(context, metadataId)
    }

    /**
     * Item->similar via the always-on content model's `similar` signature. Its endpoint configuration is
     * infrastructure configuration, independent of PERSONALIZED strategy activation. Operational embedding
     * storage is training input, never an alternate recommendation-serving path.
     */
    private suspend fun contentSimilar(
        metadataId: UUID,
        limit: Int,
        context: ServingContext,
        languageTag: String,
    ): List<Recommendation>? {
        return modelRecommendations(context, languageTag, limit, sourceId = metadataId, personalized = false)
    }

    override suspend fun getCoEngaged(
        metadataId: UUID,
        profileId: UUID?,
        limit: Int,
        mlEnabled: Boolean,
        modelVersion: Long?,
        contextType: String?,
        languageTag: String?,
    ): List<Recommendation> {
        if (limit <= 0) return emptyList()
        val context = resolveContext(contextType, modelVersion)
        val language = resolveLanguageTag(profileId, languageTag)
        val dismissed = profileId?.let { dismissalRepository.getDismissedMetadataIds(it).toSet() }.orEmpty()
        if (mlEnabled && context.model?.personalized == true) {
            modelRecommendations(context, language.resolvedLanguageTag, limit, profileId, metadataId,
                personalized = true, coEngagedOnly = true, dismissed = dismissed)?.let {
                return it.withRequestContext(context, metadataId)
            }
        }
        // An unavailable behavioral model preserves this endpoint's behavioral-only fallback.
        val result = ArrayList<Recommendation>()
        var offset = 0L
        while (result.size < limit) {
            val page = coEngagementRepository.getBySource(
                metadataId, context.model != null, limit, language.resolvedLanguageTag,
                includedContentTypePrefixes = context.includedContentTypePrefixes,
                excludedContentTypePrefixes = context.excludedContentTypePrefixes,
                includedAttributeTypes = context.includedAttributeTypes,
                excludedAttributeTypes = context.excludedAttributeTypes,
                offset = offset,
            )
            if (page.isEmpty()) break
            offset += page.size
            val candidates = page.map { it.toRecommendation() }
                .filter { it.metadataId != metadataId && it.metadataId !in dismissed }
            result += filterEligible(candidates, context, language)
        }
        return result.take(limit).withRequestContext(context, metadataId)
    }

    /**
     * Min-max normalizes [values] to [0, 1]. When every value is equal (no spread, so the signal carries
     * no ordering information) each maps to 0.5 — neutral, so it neither biases nor cancels the blend.
     */
    private fun normalizeToUnit(values: List<Double>): List<Double> {
        val min = values.min()
        val max = values.max()
        val range = max - min
        return if (range <= 0.0) values.map { 0.5 } else values.map { (it - min) / range }
    }

    private fun CoEngagement.toRecommendation() = Recommendation(
        metadataId = coEngagedMetadataId,
        strategyId = strategyId,
        score = score,
        reason = reason ?: "Frequently engaged with together",
        sources = setOf(RecommendationStrategyType.CO_ENGAGEMENT.toSource()),
    )

    override suspend fun getRecommended(
        metadataId: UUID,
        profileId: UUID?,
        limit: Int,
        mlEnabled: Boolean,
        modelVersion: Long?,
        contextType: String?,
        languageTag: String?,
    ): List<Recommendation> {
        if (limit <= 0) return emptyList()
        val context = resolveContext(contextType, modelVersion)
        val language = resolveLanguageTag(profileId, languageTag)
        val dismissed = profileId?.let { dismissalRepository.getDismissedMetadataIds(it).toSet() }.orEmpty()
        if (mlEnabled && context.model?.personalized == true) {
            modelRecommendations(context, language.resolvedLanguageTag, limit, profileId, metadataId,
                personalized = true, dismissed = dismissed)?.let { return it.withRequestContext(context, metadataId) }
        }
        return modelRecommendations(context, language.resolvedLanguageTag, limit, sourceId = metadataId,
            personalized = false, dismissed = dismissed).orEmpty().withRequestContext(context, metadataId)
    }

    /** Min-max normalizes a candidate set's scores into [0, 1]; a uniform set maps to neutral `0.5`. */
    private fun normalizeScores(recommendations: List<Recommendation>): List<Recommendation> {
        val norm = normalizeToUnit(recommendations.map { it.score })
        return recommendations.mapIndexed { i, rec -> rec.copy(score = norm[i]) }
    }

    /**
     * Applies the live content eligibility flags after candidate generation. This also protects cached
     * personalized feeds and model versions trained before an item was made non-recommendable.
     */
    private suspend fun filterEligible(
        recommendations: List<Recommendation>,
        context: ServingContext,
        language: RecommendationLanguage,
    ): List<Recommendation> {
        if (recommendations.isEmpty()) return recommendations
        val metadataIds = recommendations.mapNotNull { it.metadataId }.distinct()
        val collectionIds = recommendations.mapNotNull { it.collectionId }.distinct()
        val recommendableMetadata = if (metadataIds.isEmpty()) {
            emptySet()
        } else {
            recommendationRepository.getEligibleMetadataIds(
                metadataIds,
                language.resolvedLanguageTag,
            ).toSet()
        }
        val recommendableCollections = if (collectionIds.isEmpty()) {
            emptyMap()
        } else {
            recommendationRepository.getEligibleCollections(
                collectionIds,
                context.type,
                language.resolvedLanguageTag,
                language.sourceLanguageTag,
            ).associateBy { it.collectionId }
        }
        return recommendations.mapNotNull { recommendation ->
            when {
                recommendation.metadataId != null -> recommendation.takeIf {
                    recommendation.metadataId in recommendableMetadata
                }
                recommendation.collectionId != null -> recommendableCollections[recommendation.collectionId]?.let {
                    recommendation.copy(collectionLanguageTag = it.languageTag)
                }
                else -> null
            }
        }
    }

    override suspend fun getTrending(
        offset: Long,
        limit: Int,
        contextType: String?,
        languageTag: String?,
    ): List<Recommendation> {
        val context = resolveContext(contextType)
        val resolvedLanguage = resolveLanguageTag(languageTag)
        return trendingCandidates(context, resolvedLanguage, offset.coerceAtLeast(0), limit).withRequestContext(context)
    }

    override suspend fun invalidatePersonalizedFeeds() {
        feedCache.clear()
    }

    /** Reads the materialized trending pool from the requested cached context facet. */
    private suspend fun trendingCandidates(
        context: ServingContext,
        language: RecommendationLanguage,
        offset: Long,
        limit: Int,
    ): List<Recommendation> {
        val trendingStrategies = strategyRepository.getActive().filter {
            it.type == RecommendationStrategyType.TRENDING
        }
        if (trendingStrategies.isEmpty()) return emptyList()
        return recommendationRepository.getByStrategyId(
            trendingStrategies.first().id,
            context.type,
            offset,
            limit,
            language.resolvedLanguageTag,
            language.sourceLanguageTag,
            metadataEnabled = context.model != null,
            includedContentTypePrefixes = context.includedContentTypePrefixes,
            excludedContentTypePrefixes = context.excludedContentTypePrefixes,
            includedAttributeTypes = context.includedAttributeTypes,
            excludedAttributeTypes = context.excludedAttributeTypes,
        ).map { it.withSource(trendingStrategies.first().type.toSource()) }
    }

    override suspend fun dismiss(profileId: UUID, metadataId: UUID?, collectionId: UUID?) {
        dismissalRepository.add(RecommendationDismissal(profileId = profileId, metadataId = metadataId, collectionId = collectionId))
        invalidateProfileFeedCache(profileId)
    }

    override suspend fun undismiss(profileId: UUID, metadataId: UUID?, collectionId: UUID?) {
        if (metadataId != null) {
            dismissalRepository.removeByMetadata(profileId, metadataId)
        }
        if (collectionId != null) {
            dismissalRepository.removeByCollection(profileId, collectionId)
        }
        invalidateProfileFeedCache(profileId)
    }

    /**
     * A profile owns multiple context/language feed keys, so dismissal changes invalidate them by profile
     * prefix. The current NATS cache enumerates its KV keys for this operation, which is acceptable at the
     * present scale. Rework this invalidation before using Redis for recommendation feeds: its prefix removal
     * performs a key scan and will have poor performance on a large cache.
     */
    private suspend fun invalidateProfileFeedCache(profileId: UUID) {
        feedCache.remove(
            RecommendationFeedCacheKeyId(profileId),
            keyPrefix = true,
        )
    }

    override suspend fun removeExpired(): Long {
        return recommendationRepository.deleteExpired()
    }

    /**
     * Gives a recommendation a fallback [reason] and marks it as a fallback via [FALLBACK_CONTEXT].
     * Fallbacks are computed at read time and never persisted, so the flag rides in the (unpersisted)
     * `context` of the returned object rather than a column — `RecommendationResult.fallback` reads it back.
     */
    private fun Recommendation.asFallback(reason: String): Recommendation =
        copy(context = FALLBACK_CONTEXT, reason = reason)

    /** A cold-start result has already exhausted its fallback sources and must not trigger them again. */
    private fun Recommendation.isFallback(): Boolean =
        ((context as? JsonObject)?.get("fallback") as? JsonPrimitive)?.booleanOrNull == true

    /** Merges attribution for overlapping candidates, retaining the first candidate's rank and fields. */
    private fun List<Recommendation>.mergeSources(): List<Recommendation> {
        val merged = linkedMapOf<Pair<UUID?, UUID?>, Recommendation>()
        for (candidate in this) {
            val key = candidate.metadataId to candidate.collectionId
            val existing = merged[key]
            merged[key] = existing?.copy(sources = existing.sources + candidate.sources) ?: candidate
        }
        return merged.values.toList()
    }

    /** Adds one attribution source without duplicating sources already attached upstream. */
    private fun Recommendation.withSource(source: RecommendationSource): Recommendation =
        copy(sources = sources + source)

    /** Maps a persisted strategy type to the source category exposed on returned recommendations. */
    private fun RecommendationStrategyType.toSource(): RecommendationSource = when (this) {
        RecommendationStrategyType.TRENDING -> RecommendationSource.TRENDING
        RecommendationStrategyType.CO_ENGAGEMENT -> RecommendationSource.CO_ENGAGEMENT
        RecommendationStrategyType.COHORT_CO_ENGAGEMENT -> RecommendationSource.COHORT_CO_ENGAGEMENT
        RecommendationStrategyType.PERSONALIZED -> RecommendationSource.PERSONALIZED_MODEL
    }

    companion object {
        private val log = LoggerFactory.getLogger(RecommendationServiceImpl::class.java)

        /** Marks a returned recommendation as a read-time fallback (not an actual personalized recommendation). */
        val FALLBACK_CONTEXT: JsonElement = JsonObject(mapOf("fallback" to JsonPrimitive(true)))
        private val REQUEST_CONTEXT_KEYS = setOf(
            "recommendation_context", "recommendation_model_version", "recommendation_source_id", "recommendation_request_id",
        )

        /** How long a profile's live-computed feed is cached before it's recomputed from the model. */
        private val FEED_CACHE_TTL = 5.minutes

        /** Candidate count cached for the default context before request pagination is applied. */
        private const val FEED_CANDIDATE_CACHE_SIZE = 500

        private const val PROFILE_LOCALE_ATTRIBUTE_TYPE = "bosca.profiles.locale"
        private const val PROFILE_LOCALE_ATTRIBUTE_FIELD = "locale"

        /**
         * Over-fetch multiplier used when querying candidates for the assembler.
         * The assembler may filter out items during deduplication and diversity
         * capping, so fetching more candidates than the final limit ensures
         * enough results survive the pipeline.
         */
        private const val ASSEMBLER_FETCH_MULTIPLIER = 3

    }
}
