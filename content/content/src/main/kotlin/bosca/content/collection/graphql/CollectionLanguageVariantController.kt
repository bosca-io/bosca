package bosca.content.collection.graphql

import bosca.content.collection.model.CollectionCacheKeyId
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.graphql.BatchFilter
import bosca.graphql.BatchItem
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.slug.service.SlugService

@TypeController
class CollectionLanguageVariantController(
    private val slugService: SlugService,
    private val collectionService: CollectionService,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
) : GraphQLController<CollectionLanguageVariant> {

    @Field
    fun languageTag(variant: CollectionLanguageVariant) = variant.languageTag

    @Field
    fun name(variant: CollectionLanguageVariant) = variant.name

    @Field
    fun description(variant: CollectionLanguageVariant) = variant.description

    @Field
    fun attributes(variant: CollectionLanguageVariant) = variant.attributes

    @Field
    fun public(variant: CollectionLanguageVariant) = variant.public

    @Field
    fun publicList(variant: CollectionLanguageVariant) = variant.publicList

    @Field
    fun publicSupplementary(variant: CollectionLanguageVariant) = variant.publicSupplementary

    @Field
    fun searchable(variant: CollectionLanguageVariant) = variant.searchable

    @Field
    fun recommendable(variant: CollectionLanguageVariant) = variant.recommendable

    @Field
    fun ready(variant: CollectionLanguageVariant) = variant.ready

    @Field
    fun workflow(variant: CollectionLanguageVariant) = CollectionWorkflow(variant)

    @Field
    suspend fun metadataRelationships(
        authentication: AuthenticationContext?,
        batch: Batch<CollectionCacheKeyId, List<CollectionLanguageVariantMetadataRelationship>>
    ) {
        batch.filter = object : BatchFilter<CollectionCacheKeyId, List<CollectionLanguageVariantMetadataRelationship>> {
            override suspend fun filter(items: List<BatchItem<CollectionCacheKeyId, List<CollectionLanguageVariantMetadataRelationship>>>): List<List<CollectionLanguageVariantMetadataRelationship>?> {
                val relationships = items.flatMap { it.data ?: emptyList() }
                val relationshipMetadata = Batch<MetadataCacheKeyId, Metadata>(keys = relationships.map { MetadataCacheKeyId(it.id2) })
                metadataService.getByIdBatched(relationshipMetadata)
                val results = relationshipMetadata.getResults().filterNotNull()
                val allowed = metadataPermissionEvaluator.isAllowed(authentication, results, PermissionAction.VIEW)
                val allowedMap = results.mapIndexed { index, metadata -> metadata.id to allowed[index] }.toMap()
                return items.map {
                    it.data?.filter { allowedMap[it.id2] ?: false } ?: emptyList()
                }
            }
        }
        collectionService.addVariantMetadataRelationshipsToBatch(batch)
    }

    @Field
    suspend fun slug(batch: Batch<CollectionCacheKeyId, String>) {
        slugService.addCollectionSlugsToBatch(batch)
    }
}
