package bosca.recommendations.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionCacheKeyId
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.ICollection
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.Batch
import bosca.graphql.BatchContext
import bosca.graphql.BatchLoaderEnvironment
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.recommendations.model.Recommendation
import bosca.recommendations.model.RecommendationBatchKey
import bosca.recommendations.model.RecommendationSource
import bosca.recommendations.model.RecommendationStrategy
import bosca.recommendations.service.RecommendationStrategyService
import bosca.recommendations.service.RecommendationService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private data class ResolvedCollectionRecommendation(
    val batchIndex: Int,
    val collection: Collection,
    val permissionTarget: ICollection,
)

/**
 * Resolves scalar and relational fields on the RecommendationResult GraphQL type.
 * Maps the internal recommendation model to the API surface, lazily loading the
 * associated metadata, collection, and strategy entities only when requested.
 */
@TypeController(type = "RecommendationResult")
class RecommendationController(
    private val strategyService: RecommendationStrategyService,
    private val metadataService: MetadataService,
    private val collectionService: CollectionService,
    private val groupEvaluator: GroupEvaluator,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    private val recommendationService: RecommendationService,
) : GraphQLController<Recommendation> {

    @Field
    fun id(recommendation: Recommendation): UUID = recommendation.id

    @Field
    fun metadataId(recommendation: Recommendation): UUID? = recommendation.metadataId

    @Field
    fun collectionId(recommendation: Recommendation): UUID? = recommendation.collectionId

    @Field
    fun collectionLanguageTag(recommendation: Recommendation): String? = recommendation.collectionLanguageTag

    @Field
    suspend fun metadata(authentication: AuthenticationContext?, recommendation: Recommendation): Metadata? {
        val id = recommendation.metadataId ?: return null
        val metadata = metadataService.getById(id) ?: return null
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        return metadata
    }

    @Field
    suspend fun collection(
        authentication: AuthenticationContext?,
        environment: BatchLoaderEnvironment,
        batch: Batch<RecommendationBatchKey, Collection>,
    ) {
        val recommendations = environment.keyContextsList.map {
            (it as BatchContext<*>).context as Recommendation
        }
        val collectionIds = recommendations.mapNotNull { it.collectionId }.distinct()
        if (collectionIds.isEmpty()) return

        val collections = collectionService.getByIds(collectionIds).associateBy { it.id }
        val translatedCollectionIds = recommendations.mapNotNull { recommendation ->
            val collectionId = recommendation.collectionId ?: return@mapNotNull null
            val selectedLanguageTag = recommendation.collectionLanguageTag ?: return@mapNotNull null
            val collection = collections[collectionId] ?: return@mapNotNull null
            collectionId.takeUnless { selectedLanguageTag.equals(collection.languageTag, ignoreCase = true) }
        }.distinct()
        val variantKeys = translatedCollectionIds.map(::CollectionCacheKeyId)
        val variants = if (variantKeys.isEmpty()) {
            emptyMap()
        } else {
            val variantBatch = Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>(variantKeys)
            collectionService.addLanguageVariantsToBatch(variantBatch)
            variantKeys.flatMap { variantBatch.getData(it).orEmpty() }
                .associateBy { it.id to it.languageTag.lowercase() }
        }

        val resolved = buildList {
            recommendations.forEachIndexed { index, recommendation ->
                val collectionId = recommendation.collectionId ?: return@forEachIndexed
                val collection = collections[collectionId] ?: return@forEachIndexed
                val selectedLanguageTag = recommendation.collectionLanguageTag
                val selectedVariant: CollectionLanguageVariant? = selectedLanguageTag
                    ?.takeUnless { it.equals(collection.languageTag, ignoreCase = true) }
                    ?.let { variants[collectionId to it.lowercase()] }

                if (selectedLanguageTag != null &&
                    !selectedLanguageTag.equals(collection.languageTag, ignoreCase = true) &&
                    selectedVariant == null
                ) {
                    return@forEachIndexed
                }

                add(
                    ResolvedCollectionRecommendation(
                        batchIndex = index,
                        collection = collection,
                        permissionTarget = selectedVariant ?: collection,
                    ),
                )
            }
        }
        if (resolved.isEmpty()) return

        val allowed = collectionPermissionEvaluator.isAllowed(
            authentication,
            resolved.map { it.permissionTarget },
            PermissionAction.VIEW,
        )
        resolved.forEachIndexed { index, resolvedRecommendation ->
            if (!allowed[index]) {
                groupEvaluator.throwUnauthorized()
            }
            val result = (resolvedRecommendation.permissionTarget as? CollectionLanguageVariant)?.let { variant ->
                resolvedRecommendation.collection.copy().also {
                    it.itemAttributes = resolvedRecommendation.collection.itemAttributes
                    it.defaultLanguageVariant = variant
                }
            } ?: resolvedRecommendation.collection
            batch.setData(resolvedRecommendation.batchIndex, result)
        }
    }

    @Field
    fun score(recommendation: Recommendation): Double = recommendation.score

    /** Computes same-version model attribution only when the client selects this field. */
    @Field
    suspend fun sources(
        environment: BatchLoaderEnvironment,
        batch: Batch<RecommendationBatchKey, List<RecommendationSource>>,
    ) {
        val recommendations = environment.keyContextsList.map {
            (it as BatchContext<*>).context as Recommendation
        }
        recommendationService.getSources(recommendations).forEachIndexed { index, sources ->
            batch.setData(index, sources)
        }
    }

    @Field
    fun reason(recommendation: Recommendation): String? = recommendation.reason

    @Field
    fun context(recommendation: Recommendation): JsonElement? = recommendation.context

    /**
     * Whether this is a read-time fallback rather than an actual personalized recommendation. Fallbacks
     * aren't persisted, so the flag rides in the (unpersisted) `context` of the returned recommendation —
     * `{"fallback": true}` (see `RecommendationServiceImpl.FALLBACK_CONTEXT`); everything else is `false`.
     */
    @Field
    fun fallback(recommendation: Recommendation): Boolean =
        (recommendation.context as? JsonObject)?.get("fallback")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true

    @Field
    fun expiresAt(recommendation: Recommendation): OffsetDateTime? = recommendation.expiresAt

    @Field
    fun created(recommendation: Recommendation): OffsetDateTime = recommendation.created

    @Field
    suspend fun strategy(authentication: AuthenticationContext, recommendation: Recommendation): RecommendationStrategy? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        if (recommendation.strategyId == UUID.NIL) return null
        return strategyService.getById(recommendation.strategyId)
    }
}
