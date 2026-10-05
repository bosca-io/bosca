package bosca.content.collection.service

import bosca.category.model.Category
import bosca.category.service.CategoryService
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionCacheKeyId
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
import bosca.content.collection.model.CollectionWorkflowPlan
import bosca.content.collection.model.ICollection
import bosca.content.find.FindQueryInput
import bosca.content.model.ContentRelationship
import bosca.content.transition.service.TransitioningService
import bosca.graphql.Batch
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.security.model.Principal
import bosca.serialization.UUID
import bosca.trait.model.Trait
import bosca.trait.service.TraitService
import bosca.server.content.*
import kotlinx.serialization.json.JsonElement

suspend fun CollectionService.getCategories(id: UUID, categoryService: CategoryService): List<Category> {
    val ids = getCategoryIds(id).toSet()
    if (ids.isEmpty()) return emptyList()
    val categories = categoryService.getAll()
    return categories.filter { ids.contains(it.id) }
}

suspend fun CollectionService.getTraits(id: UUID, traitService: TraitService): List<Trait> {
    val ids = getTraitIds(id).toSet()
    val categories = traitService.getAll()
    return categories.filter { ids.contains(it.id) }
}

/**
 * Service for managing collections, which are hierarchical containers for organizing content items
 * (both metadata entries and other collections). Extends [PermissionService] for access control and
 * [TransitioningService] for workflow state management.
 *
 * Collections support language variants, supplementary content, metadata relationships,
 * collaboration tracking, and template-based configuration.
 */
interface CollectionService : PermissionService<ICollection, UUID>, TransitioningService<ICollection> {

    suspend fun removeFromCache(id: UUID)

    /**
     * Retrieves a paginated list of all non-deleted collections.
     *
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of collections to return
     * @return the paginated list of collections
     */
    suspend fun getAll(offset: Long, limit: Int): List<Collection>

    /**
     * Retrieves a paginated list of soft-deleted collections.
     *
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of collections to return
     * @return the paginated list of deleted collections
     */
    suspend fun getDeleted(offset: Long, limit: Int): List<Collection>

    /**
     * Registers a batch loader for efficiently fetching language variants by collection cache key.
     *
     * @param batch the batch accumulator to populate with language variant data
     */
    suspend fun addLanguageVariantsToBatch(batch: Batch<CollectionCacheKeyId, List<CollectionLanguageVariant>>)

    /**
     * Retrieves all language variants for a given collection.
     *
     * @param id the collection identifier
     * @return the list of language variants associated with this collection
     */
    suspend fun getLanguageVariants(id: UUID): List<CollectionLanguageVariant>

    /**
     * Retrieves the requested language variant for each matching collection identifier.
     *
     * @param ids collection identifiers to resolve
     * @param languageTag the language tag shared by the requested variants
     * @return the matching language variants
     */
    suspend fun getLanguageVariants(ids: List<UUID>, languageTag: String): List<CollectionLanguageVariant>

    /**
     * Retrieves parent collections of a child collection, with pagination.
     *
     * @param id the child collection identifier
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of parent collections to return
     * @return the paginated list of parent collections
     */
    suspend fun getCollectionParents(id: UUID, offset: Long, limit: Int): List<Collection>

    /**
     * Retrieves all parent collections of a child collection.
     *
     * @param id the child collection identifier
     * @return the complete list of parent collections
     */
    suspend fun getCollectionParents(id: UUID): List<Collection>

    /**
     * Retrieves collections that contain the specified metadata item, with pagination.
     *
     * @param id the metadata identifier
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of parent collections to return
     * @return the paginated list of parent collections containing this metadata
     */
    suspend fun getMetadataParents(id: UUID, offset: Long, limit: Int): List<Collection>

    /**
     * Retrieves all collections that contain the specified metadata item.
     *
     * @param id the metadata identifier
     * @return the complete list of parent collections containing this metadata
     */
    suspend fun getMetadataParents(id: UUID): List<Collection>

    /**
     * Looks up a single collection by its identifier.
     *
     * @param id the collection identifier
     * @return the collection, or null if not found
     */
    suspend fun getById(id: UUID): Collection?

    /**
     * Retrieves multiple collections by their identifiers.
     *
     * @param ids the list of collection identifiers
     * @return the list of matching collections
     */
    suspend fun getByIds(ids: List<UUID>): List<Collection>

    /**
     * Replaces the cached recommendation context classification for a collection.
     */
    suspend fun setRecommendationContexts(
        id: UUID,
        contextTypes: List<String>,
    )

    /**
     * Registers a batch loader for efficiently fetching collections by UUID.
     *
     * @param batch the batch accumulator to populate with collection data
     */
    suspend fun addCollectionToBatch(batch: Batch<UUID, Collection>)

    /**
     * Retrieves the identifiers of all categories assigned to a collection.
     *
     * @param id the collection identifier
     * @return the list of assigned category identifiers
     */
    suspend fun getCategoryIds(id: UUID): List<UUID>

    /**
     * Retrieves the identifiers of all traits assigned to a collection.
     *
     * @param id the collection identifier
     * @return the list of assigned trait identifiers
     */
    suspend fun getTraitIds(id: UUID): List<String>

    /**
     * Retrieves the child items (both metadata and sub-collections) within a collection, with
     * filtering by workflow state, language, and content type, and with pagination support.
     *
     * @param id the collection identifier
     * @param state optional workflow state filter
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of items to return
     * @param languageTag optional language tag filter
     * @param contentTypes optional list of content type filters
     * @param includeMetadata whether to include metadata items (defaults to true)
     * @param includeCollections whether to include sub-collection items (defaults to true)
     * @param languageResolutionContext optional context that resolves [languageTag] before comparing metadata language tags
     * @return the filtered, paginated list of collection items
     */
    suspend fun getItems(id: UUID, state: String?, offset: Long, limit: Int, languageTag: String?, contentTypes: List<String>?, includeMetadata: Boolean = true, includeCollections: Boolean = true, languageResolutionContext: String? = null): List<CollectionItem>

    /**
     * Retrieves the child items of a collection without using the cache, with pagination.
     * Useful when fresh data is required regardless of caching state.
     *
     * @param id the collection identifier
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of items to return
     * @return the paginated list of collection items fetched directly from the data store
     */
    suspend fun getItemsNoCache(id: UUID, offset: Long, limit: Int): List<CollectionItem>

    /**
     * Returns the total count of child items in a collection, with the same filtering
     * options as [getItems].
     *
     * @param id the collection identifier
     * @param state optional workflow state filter
     * @param languageTag optional language tag filter
     * @param contentTypes optional list of content type filters
     * @param includeMetadata whether to include metadata items (defaults to true)
     * @param includeCollections whether to include sub-collection items (defaults to true)
     * @param languageResolutionContext optional context that resolves [languageTag] before comparing metadata language tags
     * @return the total count of matching items
     */
    suspend fun getItemsCount(id: UUID, state: String?, languageTag: String?, contentTypes: List<String>?, includeMetadata: Boolean = true, includeCollections: Boolean = true, languageResolutionContext: String? = null): Long

    /**
     * Retrieves only child sub-collection items within a collection, with optional state filtering
     * and pagination.
     *
     * @param id the collection identifier
     * @param state optional workflow state filter
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of items to return
     * @return the paginated list of sub-collection items
     */
    suspend fun getCollectionItems(id: UUID, state: String?, offset: Long, limit: Int): List<CollectionItem>

    /**
     * Retrieves a specific child sub-collection item within a parent collection.
     *
     * @param id the parent collection identifier
     * @param metadataId the child collection identifier
     * @return the collection item representing the child sub-collection
     */
    suspend fun getCollectionCollectionItem(id: UUID, metadataId: UUID): CollectionItem

    /**
     * Retrieves a specific child metadata item within a parent collection.
     *
     * @param id the parent collection identifier
     * @param metadataId the child metadata identifier
     * @return the collection item representing the child metadata entry
     */
    suspend fun getCollectionMetadataItem(id: UUID, metadataId: UUID): CollectionItem

    /**
     * Returns the total count of child sub-collection items in a collection, with optional
     * state filtering.
     *
     * @param id the collection identifier
     * @param state optional workflow state filter
     * @return the total count of child sub-collection items
     */
    suspend fun getCollectionItemsCount(id: UUID, state: String?): Long

    /**
     * Retrieves only child metadata items within a collection, with optional state filtering
     * and pagination.
     *
     * @param id the collection identifier
     * @param state optional workflow state filter
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of items to return
     * @return the paginated list of metadata items
     */
    suspend fun getMetadataItems(id: UUID, state: String?, offset: Long, limit: Int): List<CollectionItem>

    /**
     * Returns the total count of child metadata items in a collection, with optional
     * state filtering.
     *
     * @param id the collection identifier
     * @param state optional workflow state filter
     * @return the total count of metadata items
     */
    suspend fun getMetadataItemsCount(id: UUID, state: String?): Long

    /**
     * Retrieves all metadata relationships for a collection.
     *
     * @param id the collection identifier
     * @return the list of metadata relationships associated with this collection
     */
    suspend fun getMetadataRelationships(id: UUID): List<CollectionMetadataRelationship>

    /**
     * Retrieves metadata relationships for a specific language variant of a collection.
     *
     * @param id the collection identifier
     * @param languageTag the language tag identifying the variant
     * @return the list of language-variant-specific metadata relationships
     */
    suspend fun getMetadataRelationships(id: UUID, languageTag: String): List<CollectionLanguageVariantMetadataRelationship>

    /**
     * Registers a batch loader for efficiently fetching metadata relationships by collection
     * cache key.
     *
     * @param batch the batch accumulator to populate with metadata relationship data
     */
    suspend fun addMetadataRelationshipsToBatch(batch: Batch<CollectionCacheKeyId, List<CollectionMetadataRelationship>>)

    /**
     * Registers a batch loader for efficiently fetching language-variant metadata relationships
     * by collection cache key.
     *
     * @param batch the batch accumulator to populate with variant metadata relationship data
     */
    suspend fun addVariantMetadataRelationshipsToBatch(batch: Batch<CollectionCacheKeyId, List<CollectionLanguageVariantMetadataRelationship>>)

    /**
     * Searches for collections matching the given query criteria using system-level permissions
     * (bypassing user-level access control).
     *
     * @param input the search query parameters
     * @return the list of collections matching the query
     */
    suspend fun findBySystem(input: FindQueryInput): List<Collection>

    /**
     * Searches for collections matching the given query criteria, applying the current
     * user's permission context.
     *
     * @param input the search query parameters
     * @return the list of collections matching the query
     */
    suspend fun find(input: FindQueryInput): List<Collection>

    /**
     * Returns the total count of collections matching the given query criteria.
     *
     * @param input the search query parameters
     * @return the total count of matching collections
     */
    suspend fun findCount(input: FindQueryInput): Long

    /**
     * Retrieves all supplementary content entries attached to a collection.
     *
     * @param id the collection identifier
     * @return the list of supplementary content entries
     */
    suspend fun getSupplementary(id: UUID): List<CollectionSupplementary>

    /**
     * Looks up a single supplementary content entry by its identifier.
     *
     * @param id the supplementary content identifier
     * @return the supplementary content entry, or null if not found
     */
    suspend fun getSupplementaryById(id: UUID): CollectionSupplementary?

    /**
     * Records that a supplementary content file has been uploaded, storing its content type
     * and size.
     *
     * @param id the supplementary content identifier
     * @param contentType the MIME type of the uploaded content, or null if unknown
     * @param contentLength the size of the uploaded content in bytes
     */
    suspend fun setSupplementaryUploaded(id: UUID, contentType: String?, contentLength: Long)

    /**
     * Retrieves the workflow execution plans for a collection.
     *
     * @param id the collection identifier
     * @return the list of workflow plans associated with the collection
     */
    suspend fun getPlans(id: UUID): List<CollectionWorkflowPlan>

    /**
     * Expands a collection by resolving its metadata items into search results, with optional
     * state filtering and pagination. Useful for rendering collection contents in search-like views.
     *
     * @param collection the collection to expand
     * @param state optional workflow state filter
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of results to return
     * @return the paginated list of expanded metadata results
     */
    suspend fun expandMetadata(collection: Collection, state: String?, offset: Long, limit: Int): List<CollectionFindResult>

    /**
     * Returns the total count of metadata items in an expanded collection view, with optional
     * state filtering.
     *
     * @param collection the collection to count expanded metadata for
     * @param state optional workflow state filter
     * @return the total count of expanded metadata items
     */
    suspend fun expandMetadataCount(collection: Collection, state: String?): Long

    /**
     * Creates a new root-level collection (one with no parent).
     *
     * @param input the collection definition to create
     * @return the newly created root collection
     */
    suspend fun addRoot(input: CollectionInput): Collection

    /**
     * Creates a new collection, optionally as a child of an existing parent collection.
     *
     * @param input the collection definition to create
     * @param parent the parent collection to nest under, or null for a root collection
     * @param parentItemAttributes optional JSON attributes to set on the parent-child relationship
     * @return the newly created collection
     */
    suspend fun add(input: CollectionInput, parent: Collection? = null, parentItemAttributes: JsonElement? = null): Collection

    /**
     * Adds a new language variant to a collection.
     *
     * @param variant the language variant definition
     * @return the newly created language variant
     */
    suspend fun addLanguageVariant(variant: CollectionLanguageVariantInput): CollectionLanguageVariant

    /**
     * Updates an existing language variant of a collection.
     *
     * @param variant the updated language variant definition
     * @return the modified language variant
     */
    suspend fun editLanguageVariant(variant: CollectionLanguageVariantInput): CollectionLanguageVariant

    /**
     * Removes a language variant from a collection.
     *
     * @param id the collection identifier
     * @param languageTag the language tag of the variant to remove
     */
    suspend fun deleteLanguageVariant(id: UUID, languageTag: String)

    /**
     * Adds a child sub-collection to a parent collection.
     *
     * @param id the parent collection identifier
     * @param childId the child collection identifier to add
     * @param attributes optional JSON attributes for the parent-child relationship
     */
    suspend fun addCollectionItem(id: UUID, childId: UUID, attributes: JsonElement?)

    /**
     * Adds a metadata item as a child of a collection.
     *
     * @param id the parent collection identifier
     * @param childId the metadata identifier to add
     * @param attributes optional JSON attributes for the parent-child relationship
     */
    suspend fun addMetadataItem(id: UUID, childId: UUID, attributes: JsonElement?)

    /**
     * Updates an existing collection with new values.
     *
     * @param id the collection identifier
     * @param input the updated collection definition
     * @return the modified collection
     */
    suspend fun edit(id: UUID, input: CollectionInput): Collection

    /**
     * Soft-deletes a collection by marking it as deleted without removing it from storage.
     *
     * @param id the collection identifier to mark as deleted
     */
    suspend fun markDeleted(id: UUID)

    /**
     * Replaces all user-defined attributes on a collection.
     *
     * @param id the collection identifier
     * @param attributes the new attributes JSON to set
     */
    suspend fun setAttributes(id: UUID, attributes: JsonElement)

    /**
     * Deep-merges the provided attributes into the collection's existing attributes.
     *
     * @param id the collection identifier
     * @param attributes the attributes JSON to merge
     */
    suspend fun mergeAttributes(id: UUID, attributes: JsonElement)

    /**
     * Replaces all system-managed attributes on a collection. System attributes are
     * distinct from user-defined attributes and are typically managed by internal processes.
     *
     * @param id the collection identifier
     * @param attributes the new system attributes JSON to set
     */
    suspend fun setSystemAttributes(id: UUID, attributes: JsonElement)

    /**
     * Deep-merges attributes into a specific metadata child item's relationship attributes
     * within this collection.
     *
     * @param id the parent collection identifier
     * @param itemId the metadata item identifier
     * @param attributes the attributes JSON to merge
     */
    suspend fun mergeMetadataItemAttributes(id: UUID, itemId: UUID, attributes: JsonElement)

    /**
     * Deep-merges attributes into a specific child sub-collection's relationship attributes
     * within this collection.
     *
     * @param id the parent collection identifier
     * @param itemId the child collection identifier
     * @param attributes the attributes JSON to merge
     */
    suspend fun mergeCollectionItemAttributes(id: UUID, itemId: UUID, attributes: JsonElement)

    /**
     * Sets the locked state of a collection, preventing or allowing modifications.
     *
     * @param collection the collection to lock or unlock
     * @param locked true to lock, false to unlock
     */
    suspend fun setLocked(collection: Collection, locked: Boolean)

    /**
     * Controls the public visibility of a collection, optionally for a specific language variant.
     *
     * @param id the collection identifier
     * @param public true to make the collection publicly visible, false to restrict access
     * @param languageTag optional language tag to set visibility for a specific variant only
     */
    suspend fun setPublic(id: UUID, public: Boolean, languageTag: String? = null)

    /**
     * Controls the public visibility of a collection's item list, optionally for a specific
     * language variant.
     *
     * @param id the collection identifier
     * @param public true to make the list publicly visible, false to restrict access
     * @param languageTag optional language tag to set visibility for a specific variant only
     */
    suspend fun setPublicList(id: UUID, public: Boolean, languageTag: String? = null)

    /**
     * Controls the public visibility of a collection's supplementary content, optionally for a
     * specific language variant.
     *
     * @param id the collection identifier
     * @param public true to make supplementary content publicly visible, false to restrict access
     * @param languageTag optional language tag to set visibility for a specific variant only
     */
    suspend fun setPublicSupplementary(id: UUID, public: Boolean, languageTag: String? = null)

    /**
     * Controls whether a collection or one of its language variants appears in search results.
     *
     * @param id the collection identifier
     * @param searchable true to make the collection searchable, false to exclude from search
     * @param languageTag optional language tag to update a specific variant only
     */
    suspend fun setSearchable(id: UUID, searchable: Boolean, languageTag: String? = null)

    /**
     * Controls whether a collection or one of its language variants may be returned as a
     * recommendation candidate.
     *
     * @param id the collection identifier
     * @param recommendable true to allow recommendations, false to exclude the collection
     * @param languageTag optional language tag to update a specific variant only
     */
    suspend fun setRecommendable(id: UUID, recommendable: Boolean, languageTag: String? = null)

    /**
     * Marks a collection as ready for publishing, recording the principal who approved it.
     *
     * @param collection the collection to mark as ready
     * @param principal the authenticated principal performing the action
     */
    suspend fun setReady(collection: ICollection, principal: Principal)

    /**
     * Marks a collection as ready for publishing by identifier, optionally for a specific
     * language variant.
     *
     * @param id the collection identifier
     * @param principal the authenticated principal performing the action
     * @param languageTag optional language tag to mark only a specific variant as ready
     */
    suspend fun setReady(id: UUID, principal: Principal, languageTag: String? = null)

    /**
     * Revokes the ready status of a collection, returning it to a non-ready state.
     *
     * @param item the collection to mark as not ready
     */
    suspend fun setNotReady(item: ICollection)

    /**
     * Replaces the complete set of categories assigned to a collection.
     *
     * @param id the collection identifier
     * @param categoryIds the new list of category identifiers to assign
     */
    suspend fun setCategories(id: UUID, categoryIds: List<UUID>)

    /**
     * Assigns a template to a collection, defining its structure and configuration.
     *
     * @param collectionId the collection identifier
     * @param templateId the template identifier to assign
     * @param templateVersion the version of the template to use
     */
    suspend fun setTemplate(collectionId: UUID, templateId: UUID, templateVersion: Int)

    /**
     * Sets the ordering configuration for child items within a collection.
     *
     * @param id the collection identifier
     * @param ordering the JSON ordering definition
     */
    suspend fun setCollectionOrdering(id: UUID, ordering: JsonElement)

    /**
     * Removes a child sub-collection from a parent collection.
     *
     * @param id the parent collection identifier
     * @param childId the child collection identifier to remove
     */
    suspend fun removeCollectionItem(id: UUID, childId: UUID)

    /**
     * Removes a metadata item from a collection.
     *
     * @param id the collection identifier
     * @param metadataId the metadata identifier to remove
     */
    suspend fun removeMetadataItem(id: UUID, metadataId: UUID)

    /**
     * Replaces the relationship attributes on a metadata child item within a collection.
     *
     * @param id the parent collection identifier
     * @param itemId the metadata item identifier
     * @param attributes the new attributes JSON to set, or null to clear
     * @return the updated collection, or null if not found
     */
    suspend fun setMetadataItemAttributes(
        id: UUID,
        itemId: UUID,
        attributes: JsonElement?
    ): Collection?

    /**
     * Replaces the relationship attributes on a child sub-collection within a collection.
     *
     * @param id the parent collection identifier
     * @param itemId the child collection identifier
     * @param attributes the new attributes JSON to set, or null to clear
     * @return the updated collection, or null if not found
     */
    suspend fun setCollectionItemAttributes(
        id: UUID,
        itemId: UUID,
        attributes: JsonElement?
    ): Collection?

    /**
     * Creates a new metadata relationship between a collection and a metadata item.
     *
     * @param relationship the relationship definition to create
     * @return the newly created content relationship
     */
    suspend fun addMetadataRelationship(relationship: CollectionMetadataRelationshipInput): ContentRelationship

    /**
     * Updates an existing metadata relationship between a collection and a metadata item.
     *
     * @param relationship the updated relationship definition
     * @return the modified content relationship
     */
    suspend fun editMetadataRelationship(relationship: CollectionMetadataRelationshipInput): ContentRelationship

    /**
     * Deletes a metadata relationship from a collection.
     *
     * @param collectionId the collection identifier
     * @param metadataId the metadata identifier in the relationship
     * @param relationship the relationship type name
     */
    suspend fun deleteMetadataRelationship(collectionId: UUID, metadataId: UUID, relationship: String)

    /**
     * Deletes a metadata relationship from a specific language variant of a collection.
     *
     * @param collectionId the collection identifier
     * @param languageTag the language tag of the variant
     * @param metadataId the metadata identifier in the relationship
     * @param relationship the relationship type name
     */
    suspend fun deleteMetadataRelationship(collectionId: UUID, languageTag: String, metadataId: UUID, relationship: String)

    /**
     * Deep-merges attributes into an existing metadata relationship on a collection.
     *
     * @param collectionId the collection identifier
     * @param metadataId the metadata identifier in the relationship
     * @param relationship the relationship type name
     * @param attributes the attributes JSON to merge
     * @return the updated content relationship
     */
    suspend fun mergeMetadataRelationshipAttributes(
        collectionId: UUID,
        metadataId: UUID,
        relationship: String,
        attributes: JsonElement
    ): ContentRelationship

    /**
     * Deep-merges attributes into an existing metadata relationship on a specific language
     * variant of a collection.
     *
     * @param collectionId the collection identifier
     * @param languageTag the language tag of the variant
     * @param metadataId the metadata identifier in the relationship
     * @param relationship the relationship type name
     * @param attributes the attributes JSON to merge
     * @return the updated content relationship
     */
    suspend fun mergeMetadataRelationshipAttributes(
        collectionId: UUID,
        languageTag: String,
        metadataId: UUID,
        relationship: String,
        attributes: JsonElement
    ): ContentRelationship

    /**
     * Attaches a new supplementary content entry to a collection.
     *
     * @param input the supplementary content definition
     * @return the newly created supplementary content entry
     */
    suspend fun addSupplementary(input: CollectionSupplementaryInput): CollectionSupplementary

    /**
     * Removes a supplementary content entry from a collection.
     *
     * @param collection the parent collection
     * @param id the supplementary content identifier to delete
     */
    suspend fun deleteSupplementary(collection: Collection, id: UUID)

    /**
     * Replaces the content of a supplementary entry with a string value.
     *
     * @param principal the authenticated principal performing the action
     * @param collection the parent collection
     * @param id the supplementary content identifier
     * @param content the new string content
     * @param contentType the MIME type of the content
     */
    suspend fun updateSupplementaryContent(
        principal: Principal,
        collection: Collection,
        id: UUID,
        content: String,
        contentType: String
    )

    /**
     * Replaces the content of a supplementary entry with an uploaded file.
     *
     * @param principal the authenticated principal performing the action
     * @param collection the parent collection
     * @param id the supplementary content identifier
     * @param content the uploaded file part
     * @param contentType the MIME type of the content
     */
    suspend fun updateSupplementaryContent(
        principal: Principal,
        collection: Collection,
        id: UUID,
        content: PartData.FileItem,
        contentType: String
    )

    /**
     * Records that a supplementary content file has been fully uploaded.
     *
     * @param id the supplementary content identifier
     * @param contentType the MIME type of the uploaded content
     * @param length the size of the uploaded content in bytes
     */
    suspend fun markSupplementaryUploaded(id: UUID, contentType: String, length: Int)

    /**
     * Marks the collaboration collections data as dirty for a collection, triggering
     * re-synchronization. Optionally scoped to a specific language variant.
     *
     * @param collectionId the collection identifier
     * @param languageTag optional language tag to scope the dirty flag to a specific variant
     */
    suspend fun markCollaborationCollectionsDirty(collectionId: UUID, languageTag: String?)

    /**
     * Marks the collaboration relationships data as dirty for a collection, triggering
     * re-synchronization. Optionally scoped to a specific language variant.
     *
     * @param collectionId the collection identifier
     * @param languageTag optional language tag to scope the dirty flag to a specific variant
     */
    suspend fun markCollaborationRelationshipsDirty(collectionId: UUID, languageTag: String?)

    /**
     * Marks the collaboration attributes data as dirty for a collection, triggering
     * re-synchronization. Optionally scoped to a specific language variant.
     *
     * @param collectionId the collection identifier
     * @param languageTag optional language tag to scope the dirty flag to a specific variant
     */
    suspend fun markCollaborationAttributesDirty(collectionId: UUID, languageTag: String?)

    /**
     * Grants a permission to a security group for a specific collection.
     *
     * @param collectionId the collection identifier
     * @param groupId the security group identifier
     * @param action the permission action to grant
     * @return the newly created collection permission
     */
    suspend fun addPermission(collectionId: UUID, groupId: UUID, action: PermissionAction): CollectionPermission

    /**
     * Revokes a permission from a security group for a specific collection.
     *
     * @param collectionId the collection identifier
     * @param groupId the security group identifier
     * @param action the permission action to revoke
     */
    suspend fun deletePermission(collectionId: UUID, groupId: UUID, action: PermissionAction)

    /**
     * Retrieves the collaboration configuration for a collection at a specific language variant.
     *
     * @param collectionId the collection identifier
     * @param languageTag the language tag identifying the collaboration variant
     * @return the collaboration configuration, or null if none exists
     */
    suspend fun getCollaboration(collectionId: UUID, languageTag: String): CollectionCollaboration?

    /**
     * Creates or updates the collaboration configuration for a collection.
     *
     * @param collaboration the collaboration configuration input
     */
    suspend fun setCollaboration(collaboration: CollectionCollaborationInput)

    /**
     * Permanently removes a collection and all associated data from storage.
     * Unlike [markDeleted], this operation is irreversible.
     *
     * @param collectionId the collection identifier to permanently delete
     */
    suspend fun permanentlyDelete(collectionId: UUID)

    /**
     * Synchronizes child items across all language variants of a collection, ensuring
     * variant collections have consistent item sets.
     *
     * @param collectionId the collection identifier whose variants should be synchronized
     */
    suspend fun syncVariantItems(collectionId: UUID)

    /**
     * Retrieves a specific language variant of a collection.
     *
     * @param id the collection identifier
     * @param languageTag the language tag of the variant
     * @return the language variant, or null if not found
     */
    suspend fun getLanguageVariant(id: UUID, languageTag: String): CollectionLanguageVariant?

    /**
     * Invalidates the cached items for a collection, forcing them to be reloaded
     * on the next access.
     *
     * @param collectionId the collection identifier whose item cache should be cleared
     */
    suspend fun removeItemsCache(collectionId: UUID)
}
