package bosca.content.collection.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.content.collection.events.CollectionCreated
import bosca.content.collection.events.CollectionDeleted
import bosca.content.collection.events.CollectionLanguageVariantDeleted
import bosca.content.collection.events.CollectionLanguageVariantMetadataRelationshipAdded
import bosca.content.collection.events.CollectionLanguageVariantMetadataRelationshipMerged
import bosca.content.collection.events.CollectionLanguageVariantMetadataRelationshipRemoved
import bosca.content.collection.events.CollectionLockedEvent
import bosca.content.collection.events.CollectionMetadataItemAdded
import bosca.content.collection.events.CollectionMetadataItemRemoved
import bosca.content.collection.events.CollectionMetadataRelationshipAdded
import bosca.content.collection.events.CollectionMetadataRelationshipMerged
import bosca.content.collection.events.CollectionMetadataRelationshipRemoved
import bosca.content.collection.events.CollectionSetNotReady
import bosca.content.collection.events.CollectionSetReady
import bosca.content.collection.events.CollectionStateChanged
import bosca.content.collection.events.CollectionStateChangedComplete
import bosca.content.collection.events.CollectionSupplementaryAdded
import bosca.content.collection.events.CollectionSupplementaryUpdated
import bosca.content.collection.events.CollectionUnlockedEvent
import bosca.content.collection.events.CollectionUpdated
import bosca.content.collection.events.dispatch
import bosca.content.collection.jobs.CollectionMetadataItemAddedJob
import bosca.content.collection.jobs.enqueue
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionCacheKeyId
import bosca.content.collection.model.CollectionCacheKeySerializer
import bosca.content.collection.model.CollectionCategory
import bosca.content.collection.model.CollectionCollaboration
import bosca.content.collection.model.CollectionCollaborationInput
import bosca.content.collection.model.CollectionFindResult
import bosca.content.collection.model.CollectionInput
import bosca.content.collection.model.CollectionItem
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.CollectionLanguageVariantInput
import bosca.content.collection.model.CollectionLanguageVariantMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationshipInput
import bosca.content.collection.model.CollectionPermission
import bosca.content.collection.model.CollectionSupplementary
import bosca.content.collection.model.CollectionSupplementaryInput
import bosca.content.collection.model.CollectionTrait
import bosca.content.collection.model.CollectionWorkflowPlan
import bosca.content.collection.model.ICollection
import bosca.content.collection.repository.CollectionCategoryRepository
import bosca.content.collection.repository.CollectionCollaborationRepository
import bosca.content.collection.repository.CollectionFindRepository
import bosca.content.collection.repository.CollectionItemRepository
import bosca.content.collection.repository.CollectionLanguageVariantRepository
import bosca.content.collection.repository.CollectionMetadataRelationshipRepository
import bosca.content.collection.repository.CollectionPermissionRepository
import bosca.content.collection.repository.CollectionRepository
import bosca.content.collection.repository.CollectionSupplementaryRepository
import bosca.content.collection.repository.CollectionTraitRepository
import bosca.content.collection.repository.CollectionVariantBatchId
import bosca.content.collection.repository.CollectionVariantId
import bosca.content.collection.repository.CollectionWorkflowPlanRepository
import bosca.content.find.FindQueryBuilder
import bosca.content.find.FindQueryInput
import bosca.content.metadata.events.MetadataUpdated
import bosca.content.metadata.events.dispatch
import bosca.content.metadata.repository.CollectionTemplateAttributeRepository
import bosca.content.metadata.repository.CollectionTemplateRepository
import bosca.content.metadata.repository.MetadataRepository
import bosca.content.collaboration.CollaborationAttribute
import bosca.content.collaboration.CollaborationAttributeWriter
import bosca.content.collaboration.CollaborationParent
import bosca.content.collaboration.CollaborationRelationship
import bosca.content.collaboration.Updater
import bosca.content.model.ContentRelationship
import bosca.content.ordering.Ordering
import bosca.content.recommendation.service.RecommendationContextClassifier
import bosca.content.transition.history.model.CollectionTransitionHistory
import bosca.content.transition.history.service.TransitionHistoryService
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.service.Transitioner
import bosca.db.DatabaseDispatcher
import bosca.db.connection
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.di.provideProvider
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.upload
import bosca.server.content.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.OffsetDateTime
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

@ServiceImplementation
class CollectionServiceImpl(
    private val repository: CollectionRepository,
    private val items: CollectionItemRepository,
    private val find: CollectionFindRepository,
    private val categories: CollectionCategoryRepository,
    private val traits: CollectionTraitRepository,
    private val relationships: CollectionMetadataRelationshipRepository,
    private val supplementary: CollectionSupplementaryRepository,
    private val plans: CollectionWorkflowPlanRepository,
    private val permissions: CollectionPermissionRepository,
    private val transitionHistory: TransitionHistoryService,
    private val objectService: ObjectStorageService,
    private val json: Json,
    private val slugService: SlugService,
    private val collaborations: CollectionCollaborationRepository,
    private val variants: CollectionLanguageVariantRepository,
    private val collectionTemplates: CollectionTemplateRepository,
    private val collectionTemplateAttributes: CollectionTemplateAttributeRepository,
    private val metadataRepository: MetadataRepository,
    private val transitioner: ObjectProvider<Transitioner>,
    private val securityService: ObjectProvider<SecurityService>,
) : CollectionService {

    private suspend fun classifyRecommendationContexts(
        collection: Collection,
        fallback: List<String> = collection.recommendationContexts,
    ): List<String> {
        val classifier = provideProvider<RecommendationContextClassifier>()
        return if (classifier.exists) {
            classifier.get().classifyCollection(collection.type.name, collection.attributes)
        } else {
            fallback
        }
    }

    private val collectionParentsCache = ServiceCache(
        "collection:parents",
        CollectionCacheKeySerializer
    ) {
        if (it.offset != null && it.limit != null) {
            items.getCollectionParents(it.id, it.offset ?: error("missing offset"), it.limit ?: error("missing limit")).map { it.collectionId }
        } else {
            items.getCollectionParents(it.id).map { it.collectionId }
        }
    }

    private val metadataParentsCache = ServiceCache(
        "metadata:parents",
        CollectionCacheKeySerializer
    ) {
        if (it.offset != null && it.limit != null) {
            items.getMetadataParents(it.id, it.offset ?: error("missing offset"), it.limit ?: error("missing limit")).map { it.collectionId }
        } else {
            items.getMetadataParents(it.id).map { it.collectionId }
        }
    }

    private val itemsCache = ServiceCache("collection:items", CollectionCacheKeySerializer) {
        getItemsOrdered(it)
    }

    private val itemsCountCache = ServiceCache("collection:items:count", CollectionCacheKeySerializer) {
        getItemsOrderedCount(it)
    }

    private val categoryIdCache = ServiceCache("collection:category:ids", CollectionCacheKeySerializer) {
        categories.getByCollectionId(it.id).map { it.categoryId }
    }

    private val traitIdCache = ServiceCache("collection:trait:ids", CollectionCacheKeySerializer) {
        traits.getByCollectionId(it.id).map { it.traitId }
    }

    private val variantLanguageCache = ServiceCache("collection:variants:ids", CollectionCacheKeySerializer, { keys, batch ->
        val variants = variants.getLanguageVariants(keys.map { it.id }).groupBy { CollectionCacheKeyId(it.id) }
        batch.setData(keys, variants)
        batch.ensureNotNull(emptyList())
    }) {
        variants.getLanguageVariants(it.id)
    }

    private val permissionsCache = ServiceCache<UUID, List<EntityPermission>>("collection:permissions", UUIDKeySerializer, { keys, batch ->
        val permissions = permissions.getCollectionPermissionsByCollectionIds(keys).groupBy { it.collectionId }
        keys.forEach {
            batch.setData(it, permissions[it] ?: emptyList())
        }
    }) {
        permissions.getCollectionPermissionsByCollectionId(it)
    }

    private val supplementaryCache = ServiceCache("collection:supplementary", CollectionCacheKeySerializer) {
        supplementary.getByCollectionId(it.id)
    }

    private val supplementaryByIdCache = ServiceCache("collection:supplementary:id", CollectionCacheKeySerializer) {
        supplementary.getById(it.supplementaryId ?: error("Supplementary ID not found"))
    }

    private val relationshipsCache = ServiceCache("collection:metadata:relationships", CollectionCacheKeySerializer, { keys, batch ->
        val relationships = relationships.getByCollectionIds(keys.map { it.id }).groupBy { it.collectionId }
        keys.forEach {
            batch.setData(it, relationships[it.id] ?: emptyList())
        }
    }) {
        relationships.getByCollectionId(it.id)
    }

    private val variantRelationshipsCache = ServiceCache("collection:variant:metadata:relationships", CollectionCacheKeySerializer, { keys, batch ->
        val collectionIds = keys.map { it.id }.distinct()
        val variantRelationships = relationships.getVariantByCollectionIds(collectionIds).groupBy { it.collectionId to it.languageTag }
        keys.forEach { key ->
            val rels = variantRelationships[key.id to (key.languageTag ?: "")] ?: emptyList()
            batch.setData(key, rels)
        }
    }) {
        relationships.getVariantByCollectionIdAndLanguageTag(it.id, it.languageTag ?: error("missing language tag"))
    }

    private val collectionCache = ServiceCache("collection", UUIDKeySerializer, { keys, batch ->
        val collections = repository.getByIds(keys).associateBy { it.id }
        batch.setData(keys, collections)
    }) {
        repository.getById(it)
    }

    override suspend fun removeFromCache(id: UUID) {
        val key = CollectionCacheKeyId(id)
        collectionParentsCache.remove(key, keyPrefix = true)
        metadataParentsCache.remove(key, keyPrefix = true)
        categoryIdCache.remove(key)
        variantLanguageCache.remove(key, keyPrefix = true)
        traitIdCache.remove(key)
        permissionsCache.remove(id)
        supplementaryCache.remove(key)
        supplementaryByIdCache.remove(key, keyPrefix = true)
        itemsCache.remove(key, keyPrefix = true)
        itemsCountCache.remove(key, keyPrefix = true)
        relationshipsCache.remove(key)
        variantRelationshipsCache.remove(key, keyPrefix = true)
        collectionCache.remove(id)
    }

    override suspend fun removeItemsCache(collectionId: UUID) {
        val key = CollectionCacheKeyId(collectionId)
        itemsCache.remove(key, keyPrefix = true)
        itemsCountCache.remove(key, keyPrefix = true)
    }

    override suspend fun getAll(offset: Long, limit: Int): List<Collection> {
        return repository.getAll(offset, limit)
    }

    override suspend fun getDeleted(offset: Long, limit: Int): List<Collection> {
        return repository.getDeleted(offset, limit)
    }

    override suspend fun addLanguageVariantsToBatch(batch: Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>) {
        variantLanguageCache.addToBatch(batch)
    }

    override suspend fun getCollectionParents(id: UUID, offset: Long, limit: Int): List<Collection> {
        val parentIds = collectionParentsCache.get(CollectionCacheKeyId(id, offset = offset, limit = limit)) ?: return emptyList()
        return parentIds.mapNotNull { getById(it) }
    }

    override suspend fun getCollectionParents(id: UUID): List<Collection> {
        val parentIds = collectionParentsCache.get(CollectionCacheKeyId(id)) ?: return emptyList()
        return parentIds.mapNotNull { getById(it) }
    }

    override suspend fun getMetadataParents(id: UUID, offset: Long, limit: Int): List<Collection> {
        val parentIds = metadataParentsCache.get(CollectionCacheKeyId(id, offset = offset, limit = limit)) ?: return emptyList()
        return parentIds.mapNotNull { getById(it) }
    }

    override suspend fun getMetadataParents(id: UUID): List<Collection> {
        val parentIds = metadataParentsCache.get(CollectionCacheKeyId(id)) ?: return emptyList()
        return parentIds.mapNotNull { getById(it) }
    }

    override suspend fun getLanguageVariants(id: UUID): List<CollectionLanguageVariant> {
        return variantLanguageCache.get(CollectionCacheKeyId(id)) ?: emptyList()
    }

    override suspend fun getById(id: UUID) = collectionCache.get(id)

    override suspend fun getByIds(ids: List<UUID>): List<Collection> {
        return collectionCache.getAll(ids).mapNotNull { it }
    }

    override suspend fun setRecommendationContexts(
        id: UUID,
        contextTypes: List<String>,
    ) {
        repository.setRecommendationContexts(id, contextTypes)
        removeFromCache(id)
    }

    override suspend fun addCollectionToBatch(batch: Batch<UUID, Collection>) {
        collectionCache.addToBatch(batch)
    }

    override suspend fun getCategoryIds(id: UUID) = categoryIdCache.get(CollectionCacheKeyId(id)) ?: emptyList()

    override suspend fun getTraitIds(id: UUID) = traitIdCache.get(CollectionCacheKeyId(id)) ?: emptyList()

    override suspend fun getPermissions(entity: ICollection) = permissionsCache.get(entity.id) ?: emptyList()

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        permissionsCache.addToBatch(batch)
    }

    override suspend fun getItems(id: UUID, state: String?, offset: Long, limit: Int, languageTag: String?, contentTypes: List<String>?, includeMetadata: Boolean, includeCollections: Boolean, languageResolutionContext: String?): List<CollectionItem> {
        return itemsCache.get(CollectionCacheKeyId(id, state, offset = offset, limit = limit, languageTag = languageTag, contentTypes = contentTypes, includeMetadata = includeMetadata, includeCollections = includeCollections, languageResolutionContext = languageResolutionContext))
            ?: emptyList()
    }

    override suspend fun getItemsNoCache(id: UUID, offset: Long, limit: Int): List<CollectionItem> = items.getItems(id, offset, limit)

    override suspend fun getItemsCount(id: UUID, state: String?, languageTag: String?, contentTypes: List<String>?, includeMetadata: Boolean, includeCollections: Boolean, languageResolutionContext: String?) =
        itemsCountCache.get(CollectionCacheKeyId(id, state, languageTag = languageTag, contentTypes = contentTypes, includeMetadata = includeMetadata, includeCollections = includeCollections, languageResolutionContext = languageResolutionContext)) ?: 0L

    override suspend fun getCollectionItems(id: UUID, state: String?, offset: Long, limit: Int) =
        state?.let { items.getCollections(id, it, offset, limit) } ?: items.getCollections(id, offset, limit)

    override suspend fun getCollectionCollectionItem(id: UUID, metadataId: UUID): CollectionItem {
        return items.getCollectionCollectionItem(id, metadataId) ?: error("missing collection item")
    }

    override suspend fun getCollectionMetadataItem(id: UUID, metadataId: UUID): CollectionItem {
        return items.getCollectionMetadataItem(id, metadataId) ?: error("missing collection item")
    }

    override suspend fun getCollectionItemsCount(id: UUID, state: String?) =
        state?.let { items.getCollectionsCount(id, it) } ?: items.getCollectionsCount(id)

    override suspend fun getMetadataItems(id: UUID, state: String?, offset: Long, limit: Int) =
        state?.let { items.getMetadata(id, state, offset, limit) } ?: items.getMetadata(id, offset, limit)

    override suspend fun getMetadataItemsCount(id: UUID, state: String?) =
        state?.let { items.getMetadataCount(id, it) } ?: items.getMetadataCount(id)

    override suspend fun getMetadataRelationships(id: UUID) = relationshipsCache.get(CollectionCacheKeyId(id)) ?: emptyList()

    override suspend fun addMetadataRelationshipsToBatch(batch: Batch<CollectionCacheKeyId, List<CollectionMetadataRelationship>>) {
        relationshipsCache.addToBatch(batch)
    }

    override suspend fun getMetadataRelationships(id: UUID, languageTag: String): List<CollectionLanguageVariantMetadataRelationship> {
        return variantRelationshipsCache.get(CollectionCacheKeyId(id, languageTag = languageTag)) ?: emptyList()
    }

    override suspend fun addVariantMetadataRelationshipsToBatch(batch: Batch<CollectionCacheKeyId, List<CollectionLanguageVariantMetadataRelationship>>) {
        variantRelationshipsCache.addToBatch(batch)
    }

    override suspend fun find(input: FindQueryInput) = find.find(input)

    override suspend fun findBySystem(input: FindQueryInput) = find.findBySystem(input)

    override suspend fun findCount(input: FindQueryInput) = find.findCount(input)

    override suspend fun getSupplementary(id: UUID): List<CollectionSupplementary> = supplementaryCache.get(CollectionCacheKeyId(id)) ?: emptyList()

    override suspend fun getSupplementaryById(id: UUID): CollectionSupplementary? = supplementary.getById(id)

    override suspend fun setSupplementaryUploaded(id: UUID, contentType: String?, contentLength: Long) {
        supplementary.setUploaded(id, contentType ?: "application/octet-stream", contentLength)
        removeFromCache(id)
        val supplementary = getSupplementaryById(id) ?: error("missing collection")
        CollectionSupplementaryUpdated(supplementary).dispatch()
    }

    override suspend fun getPlans(id: UUID): List<CollectionWorkflowPlan> = plans.getById(id)

    override suspend fun expandMetadata(collection: Collection, state: String?, offset: Long, limit: Int): List<CollectionFindResult> {
        val ordering = json.decodeFromJsonElement<List<Ordering>>(collection.ordering ?: error("missing ordering"))
        return find.expandMetadata(collection.id, ordering, state, offset, limit)
    }

    override suspend fun expandMetadataCount(collection: Collection, state: String?): Long {
        return find.expandMetadataCount(
            collection.id,
            state
        )
    }

    override suspend fun addRoot(input: CollectionInput): Collection {
        var collection = input.toCollection(json)
        val templateMetadataId = input.templateMetadataId
        val templateMetadataVersion = input.templateMetadataVersion
        val inputOrdering = input.ordering
        if (templateMetadataId != null && inputOrdering.isNullOrEmpty()) {
            val template = if (templateMetadataVersion != null) {
                collectionTemplates.getByMetadataIdAndVersion(templateMetadataId, templateMetadataVersion)
            } else {
                collectionTemplates.getByMetadataId(templateMetadataId)
            }
            template?.ordering?.let { ordering ->
                collection = collection.copy(ordering = ordering)
            }
        }
        val newCollection = repository.addRoot(
            collection.copy(recommendationContexts = classifyRecommendationContexts(collection)),
        )
        input.categoryIds?.forEach {
            categories.add(
                CollectionCategory(
                    collectionId = newCollection.id,
                    categoryId = it
                )
            )
        }
        input.traitIds?.forEach {
            traits.add(
                CollectionTrait(
                    collectionId = newCollection.id,
                    traitId = it
                )
            )
        }
        CollectionCreated(newCollection).dispatch()
        return newCollection
    }

    override suspend fun add(
        input: CollectionInput,
        parent: Collection?,
        parentItemAttributes: JsonElement?
    ): Collection = transaction {
        var collection = input.toCollection(json)
        val templateMetadataId = input.templateMetadataId
        val templateMetadataVersion = input.templateMetadataVersion
        val inputOrdering = input.ordering
        if (templateMetadataId != null && inputOrdering.isNullOrEmpty()) {
            val template = if (templateMetadataVersion != null) {
                collectionTemplates.getByMetadataIdAndVersion(templateMetadataId, templateMetadataVersion)
            } else {
                collectionTemplates.getByMetadataId(templateMetadataId)
            }
            template?.ordering?.let { ordering ->
                collection = collection.copy(ordering = ordering)
            }
            template?.defaultAttributes?.let {
                val attrs = collection.attributes
                collection = if (attrs == null) {
                    collection.copy(attributes = it)
                } else if (it is JsonObject && attrs is JsonObject) {
                    collection.copy(attributes = JsonObject(it + attrs))
                } else {
                    error("collection attributes must be an object")
                }
            }
        }
        val newCollection = repository.add(
            collection.copy(recommendationContexts = classifyRecommendationContexts(collection)),
        )
        if (input.slug.isNullOrBlank()) {
            val slug = slugService.createSlug(collection.name)
            slugService.add(
                Slug(
                    collectionId = newCollection.id,
                    slug = slug,
                )
            )
        } else {
            slugService.add(
                Slug(
                    collectionId = newCollection.id,
                    slug = input.slug ?: error("missing slug"),
                )
            )
        }
        input.categoryIds?.forEach {
            categories.add(
                CollectionCategory(
                    collectionId = newCollection.id,
                    categoryId = it
                )
            )
        }
        input.traitIds?.forEach {
            traits.add(
                CollectionTrait(
                    collectionId = newCollection.id,
                    traitId = it
                )
            )
        }
        if (parent != null) {
            addCollectionItem(
                parent.id,
                newCollection.id,
                parentItemAttributes
            )
        }
        CollectionCreated(newCollection).dispatch()
        newCollection
    }

    override suspend fun addLanguageVariant(variant: CollectionLanguageVariantInput): CollectionLanguageVariant {
        val existing = getLanguageVariant(variant.id, variant.languageTag)
        val current = variants.add(if (existing == null) {
            variant.toVariant(workflowStateId = "pending")
        } else {
            variant.toVariant(existing)
        })
        if (existing == null) {
            val englishSlug = slugService.getCollectionSlug(variant.id, null)
            if (englishSlug != null) {
                slugService.add(
                    Slug(
                        collectionId = variant.id,
                        slug = "${variant.languageTag}-${englishSlug}",
                        languageTag = variant.languageTag
                    )
                )
            }
        }
        removeFromCache(variant.id)
        CollectionUpdated(current).dispatch()
        return current
    }

    override suspend fun editLanguageVariant(variant: CollectionLanguageVariantInput): CollectionLanguageVariant {
        var current = getLanguageVariant(variant.id, variant.languageTag) ?: error("missing collection")
        current = variants.edit(variant.toVariant(current))
        removeFromCache(variant.id)
        syncCollaboration(variant.id, current.languageTag)
        CollectionUpdated(current).dispatch()
        return current
    }

    override suspend fun deleteLanguageVariant(id: UUID, languageTag: String) {
        variants.delete(CollectionVariantId(id, languageTag))
        removeFromCache(id)
        CollectionLanguageVariantDeleted(id, languageTag).dispatch()
        CollectionUpdated(id, languageTag).dispatch()
    }

    override suspend fun addCollectionItem(id: UUID, childId: UUID, attributes: JsonElement?) = transaction {
        val item = CollectionItem(
            collectionId = id,
            childCollectionId = childId,
            attributes = attributes
        )
        items.deleteByCollectionIdAndChildCollectionId(id, childId)
        items.add(item)
        removeFromCache(id)
        collectionParentsCache.remove(CollectionCacheKeyId(childId), keyPrefix = true)
        // The child's parents changed, which affects its COLLECTION-typed template attributes.
        getById(childId)?.let { syncCollaboration(childId, it.languageTag) }
        CollectionUpdated(id).dispatch()
        CollectionUpdated(childId).dispatch()
    }
    override suspend fun addMetadataItem(id: UUID, childId: UUID, attributes: JsonElement?) = transaction {
        val item = CollectionItem(
            collectionId = id,
            childMetadataId = childId,
            attributes = attributes
        )
        items.deleteByCollectionIdAndChildMetadataId(id, childId)
        items.add(item)
        removeFromCache(id)
        metadataParentsCache.remove(CollectionCacheKeyId(childId), keyPrefix = true)
        CollectionUpdated(id).dispatch()
        MetadataUpdated(childId).dispatch()
        CollectionMetadataItemAdded(id, childId).dispatch()
    }

    override suspend fun edit(id: UUID, input: CollectionInput) = transaction {
        val existing = repository.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        val candidate = input.toCollection(existing, json)
        val updated = candidate.copy(
            recommendationContexts = classifyRecommendationContexts(candidate, existing.recommendationContexts),
        )
        val updatedCollection = repository.update(updated)
        if (!input.slug.isNullOrBlank()) {
            slugService.deleteCollectionSlug(updatedCollection.id)
            slugService.add(
                Slug(
                    collectionId = updatedCollection.id,
                    slug = input.slug ?: error("missing slug"),
                )
            )
        }
        input.categoryIds?.let {
            categories.deleteByCollectionId(updatedCollection.id)
            it.forEach {
                categories.add(
                    CollectionCategory(
                        collectionId = updatedCollection.id,
                        categoryId = it
                    )
                )
            }
        }
        input.traitIds?.let {
            traits.deleteByCollectionId(updatedCollection.id)
            it.forEach {
                traits.add(
                    CollectionTrait(
                        collectionId = updatedCollection.id,
                        traitId = it
                    )
                )
            }
        }
        removeFromCache(id)
        syncCollaboration(updatedCollection.id, updatedCollection.languageTag)
        CollectionUpdated(updatedCollection).dispatch()
        updatedCollection
    }

    override suspend fun markDeleted(id: UUID) = transaction {
        repository.markDeleted(id)
        removeFromCache(id)
        val collection = getById(id) ?: error("missing collection")
        CollectionDeleted(collection).dispatch()
    }

    override suspend fun markCollaborationCollectionsDirty(collectionId: UUID, languageTag: String?) = transaction {
        val collection = getById(collectionId) ?: error("missing collection")
        val collaboration = collaborations.getByCollectionIdForUpdate(collectionId, languageTag ?: collection.languageTag) ?: return@transaction
        val content = Updater().use {
            val content = it.setCollectionsDirty(collaboration.content)
            if (!it.areCollectionsDirty(content)) error("collections are not dirty")
            content
        }
        collaborations.setCollaboration(CollectionCollaboration(collectionId, languageTag ?: collection.languageTag, content))
    }

    override suspend fun markCollaborationRelationshipsDirty(collectionId: UUID, languageTag: String?) = transaction {
        val collection = getById(collectionId) ?: error("missing collection")
        val collaboration = collaborations.getByCollectionIdForUpdate(collectionId, languageTag ?: collection.languageTag) ?: return@transaction
        val content = Updater().use {
            val content = it.setRelationshipsDirty(collaboration.content)
            if (!it.areRelationshipsDirty(content)) error("relationships are not dirty")
            content
        }
        collaborations.setCollaboration(CollectionCollaboration(collectionId, languageTag ?: collection.languageTag, content))
    }

    override suspend fun markCollaborationAttributesDirty(collectionId: UUID, languageTag: String?) = transaction {
        val collection = getById(collectionId) ?: error("missing collection")
        val collaboration = collaborations.getByCollectionIdForUpdate(collectionId, languageTag ?: collection.languageTag) ?: return@transaction
        val content = Updater().use {
            val content = it.setAttributesDirty(collaboration.content)
            if (!it.areAttributesDirty(content)) error("attributes are not dirty")
            content
        }
        collaborations.setCollaboration(CollectionCollaboration(collectionId, languageTag ?: collection.languageTag, content))
    }

    /**
     * Re-encodes the collection's full attribute state (scalar attributes, plus
     * METADATA/COLLECTION template attributes backed by relationships and parent
     * collections) into its collaboration Yjs document. The editor reads attribute
     * values from that document — it is what powers the attribute UI — so it must
     * stay consistent with the persisted attributes after any server-side change.
     *
     * Runs within the caller's transaction: if it cannot complete, the whole
     * operation rolls back, so the persisted attributes and the collaboration
     * document never diverge (a divergence would show the user stale/wrong values).
     * No-op when the collection has no template, no template attributes, or no
     * collaboration document yet (the client seeds one from the persisted state on
     * first open).
     */
    private suspend fun syncCollaboration(collectionId: UUID, languageTag: String) {
        val base = getById(collectionId) ?: return
        val templateId = base.templateMetadataId ?: return
        val templateVersion = base.templateMetadataVersion ?: return
        val existing = collaborations.getByCollectionIdForUpdate(collectionId, languageTag) ?: return
        val templateAttributes = collectionTemplateAttributes.getByMetadataIdAndVersion(templateId, templateVersion)
            .map { CollaborationAttribute(it.key, it.type, it.list, it.location, it.configuration) }
        if (templateAttributes.isEmpty()) return

        // Attributes and relationships are language-specific (base collection vs a
        // language variant); parent collections are shared across languages.
        val isBase = languageTag == base.languageTag
        val attributes = if (isBase) base.attributes else getLanguageVariant(collectionId, languageTag)?.attributes
        val rawRelationships: List<Triple<UUID, String, JsonElement?>> = if (isBase) {
            relationships.getByCollectionId(collectionId).map { Triple(it.metadataId, it.relationship, it.attributes) }
        } else {
            relationships.getVariantByCollectionIdAndLanguageTag(collectionId, languageTag)
                .map { Triple(it.metadataId, it.relationship, it.attributes) }
        }
        val collaborationRelationships = if (rawRelationships.isEmpty()) emptyList() else {
            val metadataById = metadataRepository.getByIds(rawRelationships.map { it.first }.distinct()).associateBy { it.id }
            rawRelationships.map { (metadataId, relationship, attrs) ->
                val metadata = metadataById[metadataId]
                CollaborationRelationship(
                    metadataId = metadataId,
                    relationship = relationship,
                    attributes = attrs,
                    name = metadata?.name ?: "",
                    contentType = metadata?.contentType ?: "",
                )
            }
        }

        val parentIds = items.getCollectionParents(collectionId).map { it.collectionId }.distinct()
        val collaborationParents = if (parentIds.isEmpty()) emptyList()
        else repository.getByIds(parentIds).map { CollaborationParent(it.id, it.name, it.attributes) }

        val content = CollaborationAttributeWriter.apply(
            existing.content,
            templateAttributes,
            attributes,
            collaborationRelationships,
            collaborationParents,
        )
        collaborations.setCollaboration(CollectionCollaboration(collectionId, languageTag, content))
    }

    override suspend fun setAttributes(id: UUID, attributes: JsonElement) = transaction {
        val existing = repository.getById(id) ?: error("missing collection")
        val candidate = existing.copy(attributes = attributes)
        repository.setAttributes(id, attributes, classifyRecommendationContexts(candidate))
        removeFromCache(id)
        val collection = getById(id) ?: error("missing collection")
        syncCollaboration(collection.id, collection.languageTag)
        CollectionUpdated(collection).dispatch()
    }

    override suspend fun mergeAttributes(id: UUID, attributes: JsonElement) = transaction {
        val merged = repository.mergeAttributes(id, attributes)
        repository.setRecommendationContexts(
            merged.id,
            classifyRecommendationContexts(merged),
        )
        removeFromCache(id)
        val collection = getById(id) ?: error("missing collection")
        syncCollaboration(collection.id, collection.languageTag)
        CollectionUpdated(collection).dispatch()
    }

    override suspend fun setSystemAttributes(id: UUID, attributes: JsonElement) = transaction {
        repository.setSystemAttributes(id, attributes)
        removeFromCache(id)
        val collection = getById(id) ?: error("missing collection")
        CollectionUpdated(collection).dispatch()
    }

    override suspend fun mergeMetadataItemAttributes(id: UUID, itemId: UUID, attributes: JsonElement) = transaction {
        items.mergeMetadataAttributes(id, itemId, attributes)
        removeFromCache(id)
        val collection = getById(id) ?: error("missing collection")
        CollectionUpdated(collection).dispatch()
        MetadataUpdated(itemId).dispatch()
    }

    override suspend fun mergeCollectionItemAttributes(id: UUID, itemId: UUID, attributes: JsonElement) = transaction {
        items.mergeCollectionAttributes(id, itemId, attributes)
        removeFromCache(id)
        val collection = getById(id) ?: error("missing collection")
        CollectionUpdated(collection).dispatch()
        CollectionUpdated(itemId).dispatch()
    }

    override suspend fun setLocked(collection: Collection, locked: Boolean) = transaction {
        repository.setLocked(collection.id, locked)
        removeFromCache(collection.id)
        if (locked) {
            CollectionLockedEvent(collection).dispatch()
        } else {
            CollectionUnlockedEvent(collection).dispatch()
        }
    }

    override suspend fun setPublic(id: UUID, public: Boolean, languageTag: String?) = transaction {
        val storedLanguageTag = languageTag?.let { resolveVariantLanguageTagForUpdate(id, it) }
        if (storedLanguageTag != null) {
            variants.setPublic(id, storedLanguageTag, public)
        } else {
            repository.setPublic(id, public)
        }
        removeFromCache(id)
        CollectionUpdated(id, storedLanguageTag).dispatch()
    }

    override suspend fun setPublicList(id: UUID, public: Boolean, languageTag: String?) = transaction {
        val storedLanguageTag = languageTag?.let { resolveVariantLanguageTagForUpdate(id, it) }
        if (storedLanguageTag != null) {
            variants.setPublicList(id, storedLanguageTag, public)
        } else {
            repository.setPublicList(id, public)
        }
        removeFromCache(id)
        CollectionUpdated(id, storedLanguageTag).dispatch()
    }

    override suspend fun setPublicSupplementary(id: UUID, public: Boolean, languageTag: String?) = transaction {
        val storedLanguageTag = languageTag?.let { resolveVariantLanguageTagForUpdate(id, it) }
        if (storedLanguageTag != null) {
            variants.setPublicSupplementary(id, storedLanguageTag, public)
        } else {
            repository.setPublicSupplementary(id, public)
        }
        removeFromCache(id)
        CollectionUpdated(id, storedLanguageTag).dispatch()
    }

    override suspend fun setSearchable(id: UUID, searchable: Boolean, languageTag: String?) = transaction {
        val storedLanguageTag = languageTag?.let { resolveVariantLanguageTagForUpdate(id, it) }
        if (storedLanguageTag != null) {
            variants.setSearchable(id, storedLanguageTag, searchable)
        } else {
            repository.setSearchable(id, searchable)
        }
        removeFromCache(id)
        CollectionUpdated(id, storedLanguageTag).dispatch()
    }

    override suspend fun setRecommendable(id: UUID, recommendable: Boolean, languageTag: String?) = transaction {
        val storedLanguageTag = languageTag?.let { resolveVariantLanguageTagForUpdate(id, it) }
        if (storedLanguageTag != null) {
            variants.setRecommendable(id, storedLanguageTag, recommendable)
        } else {
            repository.setRecommendable(id, recommendable)
        }
        removeFromCache(id)
        CollectionUpdated(id, storedLanguageTag).dispatch()
    }

    private suspend fun resolveVariantLanguageTagForUpdate(id: UUID, languageTag: String): String =
        variants.getLanguageVariantForUpdate(id, languageTag)?.languageTag
            ?: throw NoSuchElementException("Collection language variant not found: $id/$languageTag")

    override suspend fun setReady(collection: ICollection, principal: Principal) {
        setReady(collection.id, principal, if (collection is CollectionLanguageVariant) collection.languageTag else null)
    }

    override suspend fun setReady(id: UUID, principal: Principal, languageTag: String?) {
        val collection = if (languageTag != null) {
            variants.setReady(id, languageTag, OffsetDateTime.now())
            removeFromCache(id)
            val variant = getLanguageVariant(id, languageTag) ?: error("missing variant")
            variant
        } else {
            repository.setReady(id, OffsetDateTime.now())
            removeFromCache(id)
            val collection = getById(id) ?: error("missing collection")
            collection
        }
        if (collection.workflowStateId == "pending") {
            try {
                val groups = securityService.get().getPrincipalGroups(principal.id)
                transitioner.get().beginTransition(
                    ImpersonatedAuthenticationContext(principal, groups),
                    BeginTransitionInput(
                        collectionId = collection.id,
                        languageTag = languageTag,
                        stateId = "processing",
                        status = "Moving from pending to processing",
                        allowProcessing = true,
                    ),
                    collection
                )
            } catch (e: Exception) {
                if (languageTag != null) {
                    variants.setNotReady(id, languageTag)
                } else {
                    repository.setNotReady(id)
                }
                removeFromCache(id)
                throw e
            }
        }
        CollectionSetReady(collection).dispatch()
    }

    override suspend fun setNotReady(item: ICollection): Unit = transaction {
        when (item) {
            is CollectionLanguageVariant -> {
                variants.setNotReady(item.id, item.languageTag)
                removeFromCache(item.id)
                val variant = getLanguageVariant(item.id, item.languageTag) ?: error("missing variant")
                CollectionSetNotReady(variant).dispatch()
            }
            is Collection -> {
                repository.setNotReady(item.id)
                removeFromCache(item.id)
                val collection = getById(item.id) ?: error("missing collection")
                CollectionSetNotReady(collection).dispatch()
            }
            else -> error("unsupported type: $item")
        }
    }

    override suspend fun setCategories(id: UUID, categoryIds: List<UUID>) = transaction {
        categories.deleteByCollectionId(id)
        categoryIds.forEach { categoryId ->
            categories.add(
                CollectionCategory(
                    collectionId = id,
                    categoryId = categoryId
                )
            )
        }
        removeFromCache(id)
        val collection = getById(id) ?: error("missing collection")
        CollectionUpdated(collection).dispatch()
    }

    override suspend fun setTemplate(collectionId: UUID, templateId: UUID, templateVersion: Int) = transaction {
        repository.setTemplate(collectionId, templateId, templateVersion)
        val collection = repository.getById(collectionId) ?: error("missing collection")
        val template = collectionTemplates.getByMetadataIdAndVersion(templateId, templateVersion) ?: error("template not found")
        template.defaultAttributes?.takeIf { it is JsonObject }?.let { attributes ->
            val updatedAttributes = JsonObject((collection.attributes?.takeIf { it is JsonObject }?.jsonObject ?: JsonObject(emptyMap())) + attributes.jsonObject)
            repository.setAttributes(
                collection.id,
                updatedAttributes,
                classifyRecommendationContexts(collection.copy(attributes = updatedAttributes)),
            )
            markCollaborationAttributesDirty(collection.id, collection.languageTag)
        }
        val ordering = collection.ordering
        if (ordering == null || (ordering is JsonArray && ordering.isEmpty())) {
            collectionTemplates.getByMetadataIdAndVersion(templateId, templateVersion)?.ordering?.let { templateOrdering ->
                repository.setOrdering(collectionId, templateOrdering)
            }
        }
        removeFromCache(collectionId)
        val updated = getById(collectionId) ?: error("missing collection")
        CollectionUpdated(updated).dispatch()
    }

    override suspend fun setCollectionOrdering(id: UUID, ordering: JsonElement) = transaction {
        repository.setOrdering(id, ordering)
        removeFromCache(id)
        val collection = getById(id) ?: error("missing collection")
        CollectionUpdated(collection).dispatch()
    }

    override suspend fun setState(item: ICollection, toStateId: String, status: String, principal: Principal?): ICollection {
        transitionHistory.add(
            CollectionTransitionHistory(
                item.id,
                item.languageTag,
                item.workflowStateId,
                toStateId,
                principal?.id,
                status,
                success = true,
                complete = true
            )
        )
        if (item is CollectionLanguageVariant) {
            variants.setState(item.id, item.languageTag, toStateId)
        } else {
            repository.setState(item.id, toStateId)
        }
        removeFromCache(item.id)
        CollectionStateChanged(item).dispatch()
        return if (item is CollectionLanguageVariant) {
            getLanguageVariant(item.id, item.languageTag) ?: error("missing collection")
        } else {
            getById(item.id) ?: error("missing collection")
        }
    }

    override suspend fun setPendingState(
        item: ICollection,
        toStateId: String,
        status: String,
        valid: OffsetDateTime?,
        principal: Principal?,
        notifyEvent: Boolean
    ): ICollection {
        transitionHistory.add(
            CollectionTransitionHistory(
                item.id,
                item.languageTag,
                item.workflowStateId,
                toStateId,
                principal?.id,
                status,
                success = true,
                complete = false
            )
        )
        if (item is CollectionLanguageVariant) {
            variants.setStatePending(item.id, item.languageTag, toStateId, valid)
        } else {
            repository.setStatePending(item.id, toStateId, valid)
        }
        removeFromCache(item.id)
        if (notifyEvent) {
            CollectionStateChanged(item).dispatch()
        }
        return if (item is CollectionLanguageVariant) {
            getLanguageVariant(item.id, item.languageTag) ?: error("missing collection")
        } else {
            getById(item.id) ?: error("missing collection")
        }
    }

    override suspend fun setPendingStateFailed(item: ICollection, status: String, principal: Principal?): ICollection {
        val pendingStateId = item.workflowStatePendingId ?: error("missing pending state id")
        transitionHistory.add(
            CollectionTransitionHistory(
                item.id,
                item.languageTag,
                item.workflowStateId,
                pendingStateId,
                principal?.id,
                status,
                success = false,
                complete = true
            )
        )
        if (item is CollectionLanguageVariant) {
            variants.setState(item.id, item.languageTag, item.workflowStateId)
        } else {
            repository.setState(item.id, item.workflowStateId)
        }
        removeFromCache(item.id)
        CollectionStateChanged(item).dispatch()
        return if (item is CollectionLanguageVariant) {
            getLanguageVariant(item.id, item.languageTag) ?: error("missing collection")
        } else {
            getById(item.id) ?: error("missing collection")
        }
    }
    override suspend fun setPendingStateComplete(item: ICollection, status: String, principal: Principal?): ICollection {
        val pendingStateId = item.workflowStatePendingId ?: error("missing pending state id")
        transitionHistory.add(
            CollectionTransitionHistory(
                item.id,
                item.languageTag,
                item.workflowStateId,
                pendingStateId,
                principal?.id,
                status,
                success = true,
                complete = true
            )
        )
        if (item is CollectionLanguageVariant) {
            variants.setState(item.id, item.languageTag, pendingStateId)
        } else {
            repository.setState(item.id, pendingStateId)
        }
        removeFromCache(item.id)
        CollectionStateChangedComplete(item).dispatch()
        return if (item is CollectionLanguageVariant) {
            getLanguageVariant(item.id, item.languageTag) ?: error("missing collection")
        } else {
            getById(item.id) ?: error("missing collection")
        }
    }

    override suspend fun removeCollectionItem(id: UUID, childId: UUID) = transaction {
        items.removeCollectionItem(id, childId)
        removeFromCache(id)
        collectionParentsCache.remove(CollectionCacheKeyId(childId), keyPrefix = true)
        // The child's parents changed, which affects its COLLECTION-typed template attributes.
        getById(childId)?.let { syncCollaboration(childId, it.languageTag) }
        val collection = getById(id) ?: error("missing collection")
        CollectionUpdated(collection).dispatch()
    }

    override suspend fun removeMetadataItem(id: UUID, metadataId: UUID) = transaction {
        items.removeMetadataItem(id, metadataId)
        removeFromCache(id)
        metadataParentsCache.remove(CollectionCacheKeyId(metadataId), keyPrefix = true)
        val collection = getById(id) ?: error("missing collection")
        CollectionUpdated(collection).dispatch()
        MetadataUpdated(metadataId).dispatch()
        CollectionMetadataItemRemoved(id, metadataId).dispatch()
    }

    override suspend fun setMetadataItemAttributes(
        id: UUID,
        itemId: UUID,
        attributes: JsonElement?
    ) = transaction {
        items.setMetadataAttributes(id, itemId, attributes)
        removeFromCache(id)
        val collection = getById(id) ?: error("missing collection")
        CollectionUpdated(collection).dispatch()
        MetadataUpdated(itemId).dispatch()
        collection
    }

    override suspend fun setCollectionItemAttributes(
        id: UUID,
        itemId: UUID,
        attributes: JsonElement?
    ) = transaction {
        items.setCollectionAttributes(id, itemId, attributes)
        removeFromCache(id)
        val collection = getById(id) ?: error("missing collection")
        CollectionUpdated(collection).dispatch()
        CollectionUpdated(itemId).dispatch()
        collection
    }

    override suspend fun addMetadataRelationship(relationship: CollectionMetadataRelationshipInput) = transaction {
        if (relationship.languageTag.isNullOrEmpty()) {
            val newRel = CollectionMetadataRelationship(
                collectionId = relationship.id,
                metadataId = relationship.metadataId,
                relationship = relationship.relationship ?: "",
                attributes = relationship.attributes
            )
            relationships.deleteByCollectionIdAndMetadataIdAndRelationship(
                relationship.id,
                relationship.metadataId,
                newRel.relationship
            )
            val addedRelationship = relationships.add(newRel)
            val collection = getById(addedRelationship.collectionId) ?: error("missing collection")
            removeFromCache(addedRelationship.collectionId)
            syncCollaboration(collection.id, collection.languageTag)
            CollectionUpdated(collection).dispatch()
            CollectionMetadataRelationshipAdded(collection, addedRelationship).dispatch()
            addedRelationship
        } else {
            val newRel = CollectionLanguageVariantMetadataRelationship(
                collectionId = relationship.id,
                metadataId = relationship.metadataId,
                languageTag = relationship.languageTag ?: error("missing language tag"),
                relationship = relationship.relationship ?: "",
                attributes = relationship.attributes
            )
            relationships.deleteVariantByCollectionIdAndLanguageTagAndMetadataIdAndRelationship(
                relationship.id,
                relationship.languageTag ?: error("missing language tag"),
                relationship.metadataId,
                newRel.relationship
            )
            val addedRelationship = relationships.addVariant(newRel)
            removeFromCache(addedRelationship.collectionId)
            val variant = getLanguageVariant(addedRelationship.collectionId, relationship.languageTag ?: error("missing language tag")) ?: error("missing variant")
            syncCollaboration(addedRelationship.collectionId, variant.languageTag)
            CollectionUpdated(variant).dispatch()
            CollectionLanguageVariantMetadataRelationshipAdded(variant, addedRelationship).dispatch()
            addedRelationship
        }
    }

    override suspend fun editMetadataRelationship(relationship: CollectionMetadataRelationshipInput) = transaction {
        if (relationship.languageTag.isNullOrEmpty()) {
            val rel = relationships.updateByCollectionIdAndMetadataIdAndRelationship(
                relationship.id,
                relationship.metadataId,
                relationship.relationship ?: "",
                relationship.attributes
            )
            val collection = getById(rel.collectionId) ?: error("missing collection")
            removeFromCache(rel.collectionId)
            syncCollaboration(collection.id, collection.languageTag)
            CollectionUpdated(collection).dispatch()
            CollectionMetadataRelationshipMerged(collection, rel).dispatch()
            rel
        } else {
            val rel = relationships.updateVariantByCollectionIdAndLanguageTagAndMetadataIdAndRelationship(
                relationship.id,
                relationship.languageTag ?: error("missing language tag"),
                relationship.metadataId,
                relationship.relationship ?: "",
                relationship.attributes
            )
            val variant = getLanguageVariant(rel.collectionId, rel.languageTag) ?: error("missing variant")
            removeFromCache(rel.collectionId)
            syncCollaboration(rel.collectionId, variant.languageTag)
            CollectionUpdated(variant).dispatch()
            CollectionLanguageVariantMetadataRelationshipMerged(variant, rel).dispatch()
            rel
        }
    }

    override suspend fun deleteMetadataRelationship(collectionId: UUID, metadataId: UUID, relationship: String) {
        val collection = getById(collectionId) ?: error("missing collection")
        deleteMetadataRelationship(collectionId, collection.languageTag, metadataId, relationship)
    }

    override suspend fun deleteMetadataRelationship(collectionId: UUID, languageTag: String, metadataId: UUID, relationship: String) = transaction {
        val collection = getById(collectionId) ?: error("missing collection")
        val baseLanguage = collection.languageTag
        val currentRelationship = if (languageTag == baseLanguage) {
            relationships.getByCollectionId(collectionId).firstOrNull { it.metadataId == metadataId && it.relationship == relationship }
        } else {
            relationships.getVariantByCollectionIdAndLanguageTag(collectionId, languageTag).firstOrNull { it.metadataId == metadataId && it.relationship == relationship }
        } ?: return@transaction

        if (languageTag == baseLanguage) {
            relationships.deleteByCollectionIdAndMetadataIdAndRelationship(
                collectionId,
                metadataId,
                currentRelationship.relationship
            )
        } else {
            relationships.deleteVariantByCollectionIdAndLanguageTagAndMetadataIdAndRelationship(
                collectionId,
                languageTag,
                metadataId,
                currentRelationship.relationship
            )
        }
        removeFromCache(collectionId)
        if (languageTag == baseLanguage) {
            syncCollaboration(collection.id, collection.languageTag)
            CollectionUpdated(collectionId).dispatch()
            CollectionMetadataRelationshipRemoved(collection, currentRelationship as CollectionMetadataRelationship).dispatch()
        } else {
            val variant = getLanguageVariant(collectionId, languageTag) ?: error("missing variant")
            syncCollaboration(collectionId, languageTag)
            CollectionUpdated(collectionId, languageTag).dispatch()
            CollectionLanguageVariantMetadataRelationshipRemoved(variant, currentRelationship as CollectionLanguageVariantMetadataRelationship).dispatch()
        }
    }

    override suspend fun mergeMetadataRelationshipAttributes(
        collectionId: UUID,
        metadataId: UUID,
        relationship: String,
        attributes: JsonElement
    ) : ContentRelationship {
        val collection = getById(collectionId) ?: error("missing collection")
        return mergeMetadataRelationshipAttributes(collectionId, collection.languageTag, metadataId, relationship, attributes)
    }

    override suspend fun mergeMetadataRelationshipAttributes(
        collectionId: UUID,
        languageTag: String,
        metadataId: UUID,
        relationship: String,
        attributes: JsonElement
    ) : ContentRelationship = transaction {
        val collection = getById(collectionId) ?: error("missing collection")
        val baseLanguage = collection.languageTag
        val mergedRelationship: ContentRelationship = if (languageTag == baseLanguage) {
            relationships.mergeAttributesByCollectionIdAndMetadataIdAndRelationship(
                collectionId,
                metadataId,
                relationship,
                attributes
            )
        } else {
            relationships.mergeVariantAttributesByCollectionIdAndLanguageTagAndMetadataIdAndRelationship(
                collectionId,
                languageTag,
                metadataId,
                relationship,
                attributes
            )
        }
        removeFromCache(collectionId)
        if (languageTag == baseLanguage) {
            val collection = getById(collectionId) ?: error("missing collection")
            syncCollaboration(collection.id, collection.languageTag)
            CollectionUpdated(collection).dispatch()
            CollectionMetadataRelationshipMerged(collection, mergedRelationship as CollectionMetadataRelationship).dispatch()
        } else {
            val variant = getLanguageVariant(collectionId, languageTag) ?: error("missing variant")
            syncCollaboration(collectionId, languageTag)
            CollectionUpdated(variant).dispatch()
            CollectionLanguageVariantMetadataRelationshipMerged(variant, mergedRelationship as CollectionLanguageVariantMetadataRelationship).dispatch()
        }
        mergedRelationship
    }

    override suspend fun addSupplementary(input: CollectionSupplementaryInput) = transaction {
        if (input.key.isEmpty()) throw IllegalArgumentException("key must not be empty")
        val supplementaryEntity = CollectionSupplementary(
            collectionId = input.collectionId,
            key = input.key,
            name = input.name,
            planId = input.planId,
            jobId = input.jobId,
            contentType = input.contentType,
            contentLength = input.contentLength?.toLong(),
            attributes = input.attributes,
            created = OffsetDateTime.now(),
            modified = OffsetDateTime.now()
        )
        val supplementary = supplementary.add(supplementaryEntity)
        removeFromCache(supplementary.collectionId)
        CollectionSupplementaryAdded(supplementary).dispatch()
        supplementary
    }

    override suspend fun deleteSupplementary(collection: Collection, id: UUID) = transaction {
        val path = objectService.getPath(collection, id)
        objectService.delete(path)
        supplementary.deleteById(id)
        removeFromCache(id)
    }

    override suspend fun updateSupplementaryContent(
        principal: Principal,
        collection: Collection,
        id: UUID,
        content: String,
        contentType: String
    ) = transaction {
        val existing = supplementary.getById(id) ?: return@transaction
        require(existing.collectionId == collection.id) { "Supplementary content does not belong to collection" }
        objectService.upload(collection, id, content)
        val updated = existing.copy(
            contentType = contentType,
            contentLength = content.length.toLong(),
            modified = OffsetDateTime.now()
        )
        val supplementary = supplementary.update(updated)
        removeFromCache(collection.id)
        CollectionSupplementaryUpdated(supplementary).dispatch()
    }

    override suspend fun updateSupplementaryContent(
        principal: Principal,
        collection: Collection,
        id: UUID,
        content: PartData.FileItem,
        contentType: String
    ) = transaction {
        val existing = supplementary.getById(id) ?: return@transaction
        require(existing.collectionId == collection.id) { "Supplementary content does not belong to collection" }
        val contentLength = objectService.upload(collection, id, content)
        val updated = existing.copy(
            contentType = contentType,
            contentLength = contentLength,
            modified = OffsetDateTime.now()
        )
        val supplementary = supplementary.update(updated)
        removeFromCache(collection.id)
        CollectionSupplementaryUpdated(supplementary).dispatch()
    }

    override suspend fun markSupplementaryUploaded(id: UUID, contentType: String, length: Int) = transaction {
        val existing = supplementary.getById(id) ?: return@transaction
        val updated = existing.copy(
            contentType = contentType,
            contentLength = length.toLong(),
            uploaded = OffsetDateTime.now(),
            modified = OffsetDateTime.now()
        )
        val supplementary = supplementary.update(updated)
        removeFromCache(supplementary.collectionId)
        CollectionSupplementaryUpdated(supplementary).dispatch()
    }

    override suspend fun addPermission(collectionId: UUID, groupId: UUID, action: PermissionAction): CollectionPermission = transaction {
        permissions.add(collectionId, groupId, action)
        removeFromCache(collectionId)
        val collection = getById(collectionId) ?: error("missing collection")
        CollectionUpdated(collection).dispatch()
        CollectionPermission(
            collectionId = collectionId,
            groupId = groupId,
            action = action
        )
    }

    override suspend fun deletePermission(collectionId: UUID, groupId: UUID, action: PermissionAction) = transaction {
        permissions.deleteByCollectionId(collectionId, groupId, action)
        removeFromCache(collectionId)
        val collection = getById(collectionId) ?: error("missing collection")
        CollectionUpdated(collection).dispatch()
    }

    override suspend fun getCollaboration(collectionId: UUID, languageTag: String) = collaborations.getByCollectionId(collectionId, languageTag)

    override suspend fun setCollaboration(collaboration: CollectionCollaborationInput) {
        collaborations.setCollaboration(CollectionCollaboration(collectionId = collaboration.collectionId, languageTag = collaboration.languageTag, content = collaboration.content))
    }

    override suspend fun permanentlyDelete(collectionId: UUID) = transaction {
        repository.deleteById(collectionId)
        removeFromCache(collectionId)
    }

    override suspend fun syncVariantItems(collectionId: UUID) {
        val items = getItemsNoCache(collectionId, 0, Int.MAX_VALUE)
        items.forEach {
            it.childMetadataId?.let {
                // TODO: add a job specific to this action
                CollectionMetadataItemAddedJob(collectionId, it).enqueue()
            }
        }
    }

    override suspend fun getLanguageVariant(id: UUID, languageTag: String): CollectionLanguageVariant? {
        return variants.getLanguageVariant(id, languageTag)
    }

    override suspend fun getLanguageVariants(ids: List<UUID>, languageTag: String): List<CollectionLanguageVariant> {
        if (ids.isEmpty()) return emptyList()
        return variants.getLanguageVariants(CollectionVariantBatchId(ids, languageTag))
    }

    private suspend fun getItemsOrdered(key: CollectionCacheKeyId): List<CollectionItem> = withContext(DatabaseDispatcher) {
        val values = mutableListOf<Any?>()
        val names = mutableListOf<String>()
        if (key.state != null) {
            values.add(key.state)
            values.add(key.state)
        }
        values.add(key.id)
        if (key.languageTag != null) {
            values.add(key.languageTag)
            values.add(key.languageTag)
            if (key.state != null) {
                values.add(key.state)
            }
            if (key.languageResolutionContext != null) {
                values.add(key.languageTag)
                values.add(key.languageTag)
                values.add(key.languageResolutionContext)
            } else {
                values.add(key.languageTag)
            }
        }
        if (key.contentTypes != null) {
            values.add(key.contentTypes)
        }
        var orderByClause: String? = null

        val collection = getById(key.id) ?: return@withContext emptyList()
        collection.ordering?.let { ordering ->
            FindQueryBuilder.buildOrderByClause(
                ordering = json.decodeFromJsonElement(ListSerializer(Ordering.serializer()), ordering),
                names = names,
                values = values,
                relationshipAttributesColumn = "collection_items.attributes",
                collectionItemAttributesColumn = "collections.attributes",
                metadataItemAttributesColumn = "metadata.attributes",
                tableAlias = "collections",
                fieldMapping = mapOf(
                    "name" to "lower(coalesce(collections.name, metadata.name))",
                    "created" to "coalesce(collections.created, metadata.created)",
                    "modified" to "coalesce(collections.modified, metadata.modified)"
                )
            )
        }?.let { (clause, _) ->
            orderByClause = clause
        }
        val query = StringBuilder(
            "select collection_items.id, collection_id, child_collection_id, child_metadata_id, collection_items.attributes as attributes from collection_items "
        )
        if (key.state != null) {
            query.append(" left join collections on (child_collection_id = collections.id and collections.workflow_state_id = ?) ")
            query.append(" left join metadata on (child_metadata_id = metadata.id and metadata.workflow_state_id = ?) ")
        } else {
            query.append(" left join collections on (child_collection_id = collections.id) ")
            query.append(" left join metadata on (child_metadata_id = metadata.id) ")
        }
        query.append(" where collection_id = ? and ((collections.id is not null and (collections.deleted is null or collections.deleted = false)")
        if (key.languageTag != null) {
            if (key.state != null) {
                query.append(" and (lower(collections.language_tag) = lower(?) or exists (select 1 from collection_language_variants clv where clv.id = collections.id and lower(clv.language_tag) = lower(?) and clv.workflow_state_id = ?))")
            } else {
                query.append(" and (lower(collections.language_tag) = lower(?) or exists (select 1 from collection_language_variants clv where clv.id = collections.id and lower(clv.language_tag) = lower(?)))")
            }
        }
        query.append(") or (metadata.id is not null and (metadata.deleted is null or metadata.deleted = false)")
        if (key.languageTag != null) {
            if (key.languageResolutionContext != null) {
                query.append(" and exists (select 1 from language_resolution_contexts lrc left join lateral (select ltm.resolved_language_tag from language_tag_mappings ltm where ltm.context_id = lrc.id and (lower(ltm.source_language_tag) = lower(?) or lower(?) like lower(ltm.source_language_tag) || '-%') order by length(ltm.source_language_tag) desc limit 1) ltr on true where lrc.key = lower(?) and lower(metadata.language_tag) = lower(coalesce(ltr.resolved_language_tag, lrc.fallback_language_tag)))")
            } else {
                query.append(" and lower(metadata.language_tag) = lower(?)")
            }
            query.append(" ")
        }
        if (key.contentTypes != null) {
            query.append(" and metadata.content_type = any(?) ")
        }
        query.append(")) ")
        if (key.includeCollections != null && key.includeCollections != true) {
            query.append(" and child_collection_id is null ")
        }
        if (key.includeMetadata != null && key.includeMetadata != true) {
            query.append(" and child_metadata_id is null ")
        }
        if (orderByClause?.isNotEmpty() == true) {
            query.append(orderByClause)
        } else {
            query.append(" order by lower(collections.name) asc, lower(metadata.name) asc")
        }
        query.append(" offset ? limit ?")
        values.add(key.offset)
        values.add(key.limit)
        val items = mutableListOf<CollectionItem>()
        val connection = connection()
        connection.useStatement(query.toString()) {
            values.forEachIndexed { index, any ->
                if (any is UUID) {
                    it.setObject(index + 1, any.toJavaUuid())
                } else if (any is List<*>) {
                    it.setArray(index + 1, connection.createArrayOf("varchar", any.toTypedArray()))
                } else {
                    it.setObject(index + 1, any)
                }
            }
            it.executeQuery().use {
                while (it.next()) {
                    val id = it.getLong("id")
                    val collectionId = it.getObject("collection_id") as java.util.UUID
                    val childCollectionId = it.getObject("child_collection_id") as java.util.UUID?
                    val childMetadataId = it.getObject("child_metadata_id") as java.util.UUID?
                    val attributes = it.getString("attributes")?.let {
                        json.parseToJsonElement(it)
                    }
                    items.add(
                        CollectionItem(
                            id = id,
                            collectionId = collectionId.toKotlinUuid(),
                            childCollectionId = childCollectionId?.toKotlinUuid(),
                            childMetadataId = childMetadataId?.toKotlinUuid(),
                            attributes = attributes
                        )
                    )
                }
            }
        }
        items
    }

    private suspend fun getItemsOrderedCount(key: CollectionCacheKeyId): Long = withContext(DatabaseDispatcher) {
        val values = mutableListOf<Any?>()
        if (key.state != null) {
            values.add(key.state)
            values.add(key.state)
        }
        values.add(key.id)
        if (key.languageTag != null) {
            values.add(key.languageTag)
            values.add(key.languageTag)
            if (key.state != null) {
                values.add(key.state)
            }
            if (key.languageResolutionContext != null) {
                values.add(key.languageTag)
                values.add(key.languageTag)
                values.add(key.languageResolutionContext)
            } else {
                values.add(key.languageTag)
            }
        }
        if (key.contentTypes != null) {
            values.add(key.contentTypes)
        }
        val query = StringBuilder(
            "select count(*) from collection_items "
        )
        if (key.state != null) {
            query.append(" left join collections on (child_collection_id = collections.id and collections.workflow_state_id = ?) ")
            query.append(" left join metadata on (child_metadata_id = metadata.id and metadata.workflow_state_id = ?) ")
        } else {
            query.append(" left join collections on (child_collection_id = collections.id) ")
            query.append(" left join metadata on (child_metadata_id = metadata.id) ")
        }
        query.append(" where collection_id = ? and ((collections.id is not null and (collections.deleted is null or collections.deleted = false)")
        if (key.languageTag != null) {
            if (key.state != null) {
                query.append(" and (lower(collections.language_tag) = lower(?) or exists (select 1 from collection_language_variants clv where clv.id = collections.id and lower(clv.language_tag) = lower(?) and clv.workflow_state_id = ?))")
            } else {
                query.append(" and (lower(collections.language_tag) = lower(?) or exists (select 1 from collection_language_variants clv where clv.id = collections.id and lower(clv.language_tag) = lower(?)))")
            }
        }
        query.append(") or (metadata.id is not null and (metadata.deleted is null or metadata.deleted = false)")
        if (key.languageTag != null) {
            if (key.languageResolutionContext != null) {
                query.append(" and exists (select 1 from language_resolution_contexts lrc left join lateral (select ltm.resolved_language_tag from language_tag_mappings ltm where ltm.context_id = lrc.id and (lower(ltm.source_language_tag) = lower(?) or lower(?) like lower(ltm.source_language_tag) || '-%') order by length(ltm.source_language_tag) desc limit 1) ltr on true where lrc.key = lower(?) and lower(metadata.language_tag) = lower(coalesce(ltr.resolved_language_tag, lrc.fallback_language_tag)))")
            } else {
                query.append(" and lower(metadata.language_tag) = lower(?)")
            }
            query.append(" ")
        }
        if (key.contentTypes != null) {
            query.append(" and metadata.content_type = any(?) ")
        }
        query.append(")) ")
        if (key.includeCollections != null && key.includeCollections != true) {
            query.append(" and child_collection_id is null ")
        }
        if (key.includeMetadata != null && key.includeMetadata != true) {
            query.append(" and child_metadata_id is null ")
        }
        val connection = connection()
        return@withContext connection.useStatement(query.toString()) {
            values.forEachIndexed { index, any ->
                if (any is UUID) {
                    it.setObject(index + 1, any.toJavaUuid())
                } else if (any is List<*>) {
                    it.setArray(index + 1, connection.createArrayOf("varchar", any.toTypedArray()))
                } else {
                    it.setObject(index + 1, any)
                }
            }
            it.executeQuery().use {
                if (it.next()) {
                    return@useStatement it.getLong(1)
                }
            }
            return@useStatement 0L
        }
    }
}
