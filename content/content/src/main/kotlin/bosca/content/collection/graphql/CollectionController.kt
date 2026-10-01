package bosca.content.collection.graphql

import bosca.category.service.CategoryService
import bosca.content.attributes.model.AttributesFilterInput
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionCacheKeyId
import bosca.content.collection.model.CollectionItem
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.model.ContentItem
import bosca.content.collection.model.ICollection
import bosca.content.collection.service.CollectionService
import bosca.content.collection.service.getCategories
import bosca.content.collection.service.getTraits
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.service.MetadataService
import bosca.content.ordering.Ordering
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.Batch
import bosca.graphql.BatchContext
import bosca.graphql.BatchFilter
import bosca.graphql.BatchItem
import bosca.graphql.BatchMapper
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import bosca.trait.service.TraitService
import bosca.graphql.server.ResolverContext
import bosca.server.ServerCall
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import bosca.graphql.BatchLoaderEnvironment

@TypeController
class CollectionController(
    private val collectionService: CollectionService,
    private val categoryService: CategoryService,
    private val traitService: TraitService,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val slugService: SlugService,
    private val json: Json
) : GraphQLController<Collection> {

    @Field
    fun id(collection: Collection) = collection.id

    @Field
    fun name(collection: Collection) = collection.name

    @Field
    fun languageTag(collection: Collection) = collection.languageTag

    @Field
    suspend fun languageVariants(authentication: AuthenticationContext?, batch: Batch<CollectionCacheKeyId, List<String>>) {
        collectionService.addLanguageVariantsToBatch(BatchMapper(batch) { variants ->
            val allowed = collectionPermissionEvaluator.isAllowed(authentication, variants ?: emptyList(), PermissionAction.VIEW)
            val languageTags = mutableListOf<String>()
            for ((index, v) in (variants ?: emptyList()).withIndex()) {
                if (!allowed[index]) continue
                languageTags.add(v.languageTag)
            }
            languageTags
        })
        batch.ensureNotNull(emptyList())
    }

    @Field
    suspend fun languageVariant(authentication: AuthenticationContext?, call: ServerCall, environment: ResolverContext, batchEnvironment: BatchLoaderEnvironment, batch: Batch<CollectionCacheKeyId, CollectionLanguageVariant>) {
        val rootLanguageTag = environment.getArgument<String>("languageTag")
        val rootLanguageTags = rootLanguageTag?.let { listOf(it.lowercase()) }
            ?: call.request.queryParameters["language"]?.let { listOf(it.lowercase()) }
            ?: call.request.cookies["_language"]?.let { listOf(it.lowercase()) }
            ?: call.request.acceptLanguageItems().mapTo(mutableSetOf()) { it.value.lowercase() }

        val collectionLanguageTags = mutableMapOf<UUID, String>()
        val collectionDefaultVariant = mutableMapOf<UUID, CollectionLanguageVariant>()

        batchEnvironment.keyContextsList.forEach { context ->
            val ctx = context as BatchContext<*>
            val collection = ctx.context as Collection
            context.arguments["languageTag"]?.toString()?.let {
                collectionLanguageTags[collection.id] = it
            }
            collection.defaultLanguageVariant?.let {
                collectionDefaultVariant[collection.id] = it
            }
        }

        collectionService.addLanguageVariantsToBatch(BatchMapper(batch) { variants ->
            variants?.firstOrNull()?.let {
                collectionDefaultVariant[it.id]?.let {
                    return@BatchMapper it
                }
            }
            val allowed = collectionPermissionEvaluator.isAllowed(authentication, variants ?: emptyList(), PermissionAction.VIEW)
            var candidate: CollectionLanguageVariant? = null
            for ((index, v) in (variants ?: emptyList()).withIndex()) {
                if (!allowed[index]) continue
                val collectionLanguageTag = collectionLanguageTags[v.id]
                if (collectionLanguageTag.equals(v.languageTag, ignoreCase = true)) return@BatchMapper v
                if (rootLanguageTags.firstOrNull() == "*") {
                    if (collectionLanguageTag == null) return@BatchMapper v
                    candidate = v
                }
                if (rootLanguageTags.contains(v.languageTag.lowercase())) {
                    if (collectionLanguageTag == null) return@BatchMapper v
                    candidate = v
                }
            }
            candidate
        })
    }

    @Field
    fun etag(collection: Collection, addHeader: Boolean) = collection.etag

    @Field
    fun labels(collection: Collection) = collection.labels

    @Field
    fun description(collection: Collection) = collection.description

    @Field
    fun type(collection: Collection) = collection.type

    @Field
    suspend fun traits(collection: Collection) =
        collectionService.getTraits(collection.id, traitService)

    @Field
    suspend fun traitIds(collection: Collection) =
        collectionService.getTraits(collection.id, traitService).map { it.id }

    @Field
    suspend fun categories(collection: Collection) =
        collectionService.getCategories(collection.id, categoryService)

    @Field
    fun ordering(collection: Collection): List<Ordering> = collection.ordering?.let { json.decodeFromJsonElement(ListSerializer(Ordering.serializer()), it) } ?: emptyList()

    @Field
    suspend fun slug(batch: Batch<CollectionCacheKeyId, String>) {
        slugService.addCollectionSlugsToBatch(batch)
    }

    @Field
    suspend fun attributes(
        authentication: AuthenticationContext?,
        collection: Collection,
        filter: AttributesFilterInput?
    ): Any? {
        var filter = filter
        if (collection.isAdvertised &&
            !collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.EDIT)
        ) {
            filter = AttributesFilterInput(
                attributes = listOf("type", "description", "published")
            )
        }
        if (filter == null) return collection.attributes
        val attributes = collection.attributes
        if (attributes !is Map<*, *>) return emptyMap<String, Any>()
        return filter.filter(attributes)
    }

    @Field
    fun systemAttributes(collection: Collection) = collection.systemAttributes

    @Field
    fun itemAttributes(collection: Collection) = collection.itemAttributes

    @Field
    fun locked(collection: Collection) = collection.locked

    @Field
    fun itemsLocked(collection: Collection) = collection.itemsLocked

    @Field
    fun public(collection: Collection) = collection.public

    @Field
    fun publicList(collection: Collection) = collection.publicList

    @Field
    fun publicSupplementary(collection: Collection) = collection.publicSupplementary

    @Field
    fun searchable(collection: Collection) = collection.searchable

    @Field
    fun recommendable(collection: Collection) = collection.recommendable

    @Field
    fun deleted(collection: Collection) = collection.deleted

    @Field
    fun created(collection: Collection) = collection.created

    @Field
    fun modified(collection: Collection) = collection.modified

    @Field
    fun ready(collection: Collection) = collection.ready

    @Field
    suspend fun supplementary(
        authentication: AuthenticationContext?,
        collection: Collection,
        key: String?,
        planId: UUID?
    ): List<CollectionSupplementaryContext> {
        if (!collectionPermissionEvaluator.isSupplementaryAllowed(
                authentication,
                collection,
                PermissionAction.VIEW
            )
        ) {
            return emptyList()
        }
        val supplementary = collectionService.getSupplementary(collection.id)
        return if (key != null) {
            supplementary.asSequence().filter { it.key == key }.map { CollectionSupplementaryContext(collection, it) }
                .toList()
        } else if (planId != null) {
            supplementary.asSequence().filter { it.planId == planId }
                .map { CollectionSupplementaryContext(collection, it) }.toList()
        } else {
            supplementary.map { CollectionSupplementaryContext(collection, it) }
        }
    }

    @Field
    suspend fun items(
        authentication: AuthenticationContext?,
        collection: Collection,
        offset: Long,
        limit: Int,
        state: String?,
        languageTag: String?,
        contentTypes: List<String>?,
        languageResolutionContext: String? = null,
    ): List<ContentItem> {
        val items = collectionService.getItems(
            collection.id,
            state,
            offset,
            limit,
            languageTag,
            contentTypes,
            languageResolutionContext = languageResolutionContext,
        )
        val collection = if (languageTag != null && collection.languageTag.lowercase() != languageTag.lowercase()) {
            collectionService.getLanguageVariant(collection.id, languageTag) ?: collection
        } else {
            collection
        }
        return items.toList(collection, authentication, languageTag)
    }

    @Field
    suspend fun itemsCount(
        authentication: AuthenticationContext?,
        collection: Collection,
        state: String?,
        languageTag: String?,
        contentTypes: List<String>?,
        languageResolutionContext: String? = null,
    ): Long {
        val collection = if (languageTag != null && collection.languageTag.lowercase() != languageTag.lowercase()) {
            collectionService.getLanguageVariant(collection.id, languageTag) ?: collection
        } else {
            collection
        }
        if (!collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.LIST)) {
            return 0L
        }
        return collectionService.getItemsCount(
            collection.id,
            state,
            languageTag,
            contentTypes,
            languageResolutionContext = languageResolutionContext,
        )
    }

    @Field
    suspend fun collections(
        authentication: AuthenticationContext?,
        collection: Collection,
        offset: Long,
        limit: Int,
        state: String?,
        languageTag: String?,
    ): List<Metadata> {
        val items = collectionService.getItems(collection.id, state, offset, limit, languageTag, null, includeMetadata = false)
        val collection = if (languageTag != null && collection.languageTag.lowercase() != languageTag.lowercase()) {
            collectionService.getLanguageVariant(collection.id, languageTag) ?: collection
        } else {
            collection
        }
        return items.toList(collection, authentication, languageTag)
    }

    @Field
    suspend fun collectionsCount(
        authentication: AuthenticationContext?,
        collection: Collection,
        state: String?,
        languageTag: String?,
    ): Long {
        val collection = if (languageTag != null && collection.languageTag.lowercase() != languageTag.lowercase()) {
            collectionService.getLanguageVariant(collection.id, languageTag) ?: collection
        } else {
            collection
        }
        if (!collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.LIST)) {
            return 0L
        }
        return collectionService.getItemsCount(collection.id, state, languageTag, null, includeMetadata = false)
    }

    @Field
    suspend fun metadata(
        authentication: AuthenticationContext?,
        collection: Collection,
        offset: Long,
        limit: Int,
        state: String?,
        languageTag: String?,
        contentTypes: List<String>?
    ): List<Metadata> {
        val items = collectionService.getItems(collection.id, state, offset, limit, languageTag, contentTypes, includeCollections = false)
        return items.toList(collection, authentication, languageTag)
    }

    @Field
    suspend fun metadataCount(
        authentication: AuthenticationContext?,
        collection: Collection,
        state: String?,
        languageTag: String?,
        contentTypes: List<String>?
    ): Long {
        val collection = if (languageTag != null && collection.languageTag.lowercase() != languageTag.lowercase()) {
            collectionService.getLanguageVariant(collection.id, languageTag) ?: collection
        } else {
            collection
        }
        if (!collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.LIST)) {
            return 0
        }
        return collectionService.getItemsCount(collection.id, state, languageTag, contentTypes, includeCollections = false)
    }

    @Field
    suspend fun expandedMetadata(
        authentication: AuthenticationContext?,
        collection: Collection,
        offset: Long,
        limit: Int,
        state: String?
    ): List<Metadata> {
        return collectionService.expandMetadata(collection, state, offset, limit).mapNotNull { expanded ->
            expanded.childMetadataId?.let {
                val metadata = metadataService.getById(it)
                if (metadata != null && metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) {
                    metadata
                } else {
                    null
                }
            }
        }
    }

    @Field
    suspend fun expandedMetadataCount(
        authentication: AuthenticationContext?,
        collection: Collection,
        state: String?
    ): Long {
        if (!collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.LIST)) {
            return 0L
        }
        return collectionService.expandMetadataCount(collection, state)
    }

    @Field
    suspend fun parentCollections(
        authentication: AuthenticationContext?,
        collection: Collection,
        offset: Long,
        limit: Int
    ): List<Collection> {
        val parents = collectionService.getCollectionParents(collection.id, offset, limit)
        return parents.mapNotNull {
            if (collectionPermissionEvaluator.isAllowed(authentication, it, PermissionAction.VIEW)) {
                it.itemAttributes = it.attributes
                it
            } else {
                null
            }
        }
    }

    @Field
    suspend fun metadataRelationships(
        authentication: AuthenticationContext?,
        batch: Batch<CollectionCacheKeyId, List<CollectionMetadataRelationship>>
    ) {
        batch.filter = object : BatchFilter<CollectionCacheKeyId, List<CollectionMetadataRelationship>> {
            override suspend fun filter(items: List<BatchItem<CollectionCacheKeyId, List<CollectionMetadataRelationship>>>): List<List<CollectionMetadataRelationship>?> {
                val relationships = items.flatMap { it.data ?: emptyList() }
                val relationshipMetadata = Batch<MetadataCacheKeyId, Metadata>(keys = relationships.map { MetadataCacheKeyId(it.metadataId) })
                metadataService.getByIdBatched(relationshipMetadata)
                val results = relationshipMetadata.getResults().filterNotNull()
                val allowed = metadataPermissionEvaluator.isAllowed(authentication, results, PermissionAction.VIEW)
                val allowedMap = results.mapIndexed { index, metadata -> metadata.id to allowed[index] }.toMap()
                return items.map {
                    it.data?.filter { allowedMap[it.metadataId] ?: false } ?: emptyList()
                }
            }
        }
        collectionService.addMetadataRelationshipsToBatch(batch)
    }

    @Field
    suspend fun permissions(authentication: AuthenticationContext?, collection: Collection): List<Permission> {
        if (!collectionPermissionEvaluator.isAllowed(
                authentication,
                collection,
                PermissionAction.MANAGE
            )
        ) return emptyList()
        return collectionService.getPermissions(collection)
            .map { Permission(it.groupId, it.action) }
    }

    @Field
    suspend fun templateMetadata(authentication: AuthenticationContext?, collection: Collection): Metadata? {
        val metadata = collection.templateMetadataId?.let { templateId ->
            collection.templateMetadataVersion?.let { metadataService.getById(templateId, it) }
                ?: metadataService.getById(templateId)
        }
        if (metadata != null && !metadataPermissionEvaluator.isAllowed(
                authentication,
                metadata,
                PermissionAction.VIEW
            )
        ) {
            return null
        }
        return metadata
    }

    private suspend fun <T : ContentItem> List<CollectionItem>.toList(
        collection: ICollection,
        authentication: AuthenticationContext?,
        languageTag: String?
    ): List<T> {
        if (!collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.LIST)) {
            return emptyList()
        }
        val result = mutableListOf<T>()
        for (item in this) {
            item.childCollectionId?.let {
                val collection = collectionService.getById(it) ?: continue
                val securityItem = if (languageTag != null && collection.languageTag.lowercase() != languageTag.lowercase()) {
                    collectionService.getLanguageVariant(it, languageTag)
                } else {
                    collection
                }
                if (securityItem != null && collectionPermissionEvaluator.isAllowed(authentication, securityItem, PermissionAction.VIEW)) {
                    collection.itemAttributes = item.attributes
                    @Suppress("UNCHECKED_CAST")
                    result.add(collection as T)
                }
            }
            item.childMetadataId?.let {
                val metadata = metadataService.getById(it) ?: continue
                if (metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) {
                    metadata.itemAttributes = item.attributes
                    @Suppress("UNCHECKED_CAST")
                    result.add(metadata as T)
                }
            }
        }
        return result
    }

    @Field
    suspend fun workflow(authenticationContext: AuthenticationContext, collection: Collection): CollectionWorkflow {
        collectionPermissionEvaluator.verifyAllowed(authenticationContext, collection, PermissionAction.EDIT)
        return CollectionWorkflow(collection)
    }
}
