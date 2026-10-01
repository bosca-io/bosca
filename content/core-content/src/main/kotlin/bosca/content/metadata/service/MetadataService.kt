package bosca.content.metadata.service

import bosca.category.model.Category
import bosca.content.collection.model.Collection
import bosca.content.embedding.model.EmbeddingChunk
import bosca.content.find.FindQueryInput
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.BibleInput
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideStepModule
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataProfile
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.model.MetadataWorkflowPlan
import bosca.content.metadata.model.SourceStatus
import bosca.content.transition.service.TransitioningService
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionInput
import bosca.security.model.PermissionService
import bosca.security.model.Principal
import bosca.serialization.UUID
import bosca.trait.model.Trait
import bosca.server.content.*
import kotlinx.serialization.json.JsonElement

/**
 * Service for managing metadata entries, which are the primary content items in the system.
 * Metadata serves as a container that can hold various content types (documents, data, guides,
 * Bibles) along with supplementary content, relationships to other metadata, categories, traits,
 * and workflow state.
 *
 * Extends [PermissionService] for access control and [TransitioningService] for workflow
 * state management.
 */
interface MetadataService : PermissionService<Metadata, UUID>, TransitioningService<Metadata> {

    suspend fun removeFromCache(metadata: Metadata)

    suspend fun removeFromCache(id: UUID, version: Int? = null)

    /**
     * Retrieves a paginated list of all non-deleted metadata entries.
     *
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of entries to return
     * @return the paginated list of metadata entries
     */
    suspend fun getAll(offset: Long, limit: Int): List<Metadata>

    /**
     * Retrieves a paginated list of soft-deleted metadata entries.
     *
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of entries to return
     * @return the paginated list of deleted metadata entries
     */
    suspend fun getDeleted(offset: Long, limit: Int): List<Metadata>

    /**
     * Looks up a metadata entry by its identifier, returning the latest version.
     *
     * @param id the metadata identifier
     * @return the metadata entry, or null if not found
     */
    suspend fun getById(id: UUID): Metadata?

    /**
     * Looks up the identifier of a language variant of a metadata entry.
     *
     * @param id the metadata identifier
     * @param languageTag the language tag of the variant
     * @return the variant's metadata identifier, or null if no variant exists for the given language
     */
    suspend fun getLanguageVariantById(id: UUID, languageTag: String): UUID?

    /**
     * Retrieves all metadata entries that are children of the specified parent metadata.
     *
     * @param id the parent metadata identifier
     * @return the list of child metadata entries
     */
    suspend fun getByParentId(id: UUID): List<Metadata>

    /**
     * Updates the display name of a metadata entry.
     *
     * @param id the metadata identifier
     * @param name the new display name
     * @return the updated metadata entry, or null if not found
     */
    suspend fun setName(id: UUID, name: String): Metadata?

    /**
     * Looks up a metadata entry by its identifier and optional version number.
     *
     * @param id the metadata identifier
     * @param version the specific version to retrieve, or null for the latest version
     * @return the metadata entry, or null if not found
     */
    suspend fun getById(id: UUID, version: Int?): Metadata?

    /**
     * Retrieves multiple metadata entries by their identifiers.
     *
     * @param ids the list of metadata identifiers
     * @return the list of matching metadata entries
     */
    suspend fun getByIds(ids: List<UUID>): List<Metadata>

    /**
     * Replaces the cached recommendation context classification for a metadata entry.
     */
    suspend fun setRecommendationContexts(
        id: UUID,
        contextTypes: List<String>,
    )

    /**
     * Registers a batch loader for efficiently fetching metadata entries by cache key.
     *
     * @param batch the batch accumulator to populate with metadata data
     */
    suspend fun getByIdBatched(batch: Batch<MetadataCacheKeyId, Metadata>)

    /**
     * Retrieves all categories assigned to a metadata entry.
     *
     * @param id the metadata identifier
     * @return the list of assigned categories
     */
    suspend fun getCategories(id: UUID): List<Category>

    /**
     * Registers a batch loader for efficiently fetching categories by metadata cache key.
     *
     * @param batch the batch accumulator to populate with category lists
     */
    suspend fun addCategoriesToBatch(batch: Batch<MetadataCacheKeyId, List<Category>>)

    /**
     * Registers a batch loader for efficiently fetching category identifiers by metadata
     * cache key.
     *
     * @param batch the batch accumulator to populate with category identifier lists
     */
    suspend fun addCategoryIdsToBatch(batch: Batch<MetadataCacheKeyId, List<UUID>>)

    /**
     * Registers a batch loader for efficiently fetching traits by metadata cache key.
     *
     * @param batch the batch accumulator to populate with trait lists
     */
    suspend fun addTraitsToBatch(batch: Batch<MetadataCacheKeyId, List<Trait>>)

    /**
     * Registers a batch loader for efficiently fetching trait identifiers by metadata
     * cache key.
     *
     * @param batch the batch accumulator to populate with trait identifier lists
     */
    suspend fun addTraitIdsToBatch(batch: Batch<MetadataCacheKeyId, List<String>>)

    /**
     * Retrieves the identifiers of all traits assigned to a metadata entry.
     *
     * @param id the metadata identifier
     * @return the list of assigned trait identifiers
     */
    suspend fun getTraitIds(id: UUID): List<String>

    /**
     * Retrieves all profiles associated with a metadata entry. Profiles represent
     * different published representations or configurations of the metadata.
     *
     * @param id the metadata identifier
     * @return the list of metadata profiles
     */
    suspend fun getProfiles(id: UUID): List<MetadataProfile>

    /**
     * Sets or clears the parent metadata relationship for a metadata entry.
     *
     * @param id the metadata identifier
     * @param parentId the parent metadata identifier, or null to clear the parent
     */
    suspend fun setParent(id: UUID, parentId: UUID?)

    /**
     * Controls whether collection membership changes should be synchronized across
     * language variants of this metadata entry.
     *
     * @param id the metadata identifier
     * @param syncVariants true to enable variant collection synchronization, false to disable
     */
    suspend fun setSyncVariantCollections(id: UUID, syncVariants: Boolean)

    /**
     * Controls whether relationship changes should be synchronized across language
     * variants of this metadata entry.
     *
     * @param id the metadata identifier
     * @param syncVariants true to enable variant relationship synchronization, false to disable
     */
    suspend fun setSyncVariantRelationships(id: UUID, syncVariants: Boolean)

    /**
     * Controls whether a metadata entry appears in search results.
     *
     * @param id the metadata identifier
     * @param searchable true to make the entry searchable, false to exclude from search
     */
    suspend fun setSearchable(id: UUID, searchable: Boolean)

    /**
     * Controls whether a metadata entry may be returned as a recommendation candidate.
     *
     * @param id the metadata identifier
     * @param recommendable true to allow recommendations, false to exclude the entry
     */
    suspend fun setRecommendable(id: UUID, recommendable: Boolean)

    /**
     * Controls whether end users may post comments on a metadata entry. Moderators
     * (content MANAGE) can comment regardless of this flag.
     *
     * @param id the metadata identifier
     * @param enabled true to allow end-user comments, false to disallow them
     */
    suspend fun setCommentsEnabled(id: UUID, enabled: Boolean)

    /**
     * Controls whether end users may post threaded replies to comments on a metadata
     * entry. Only meaningful when comments are enabled.
     *
     * @param id the metadata identifier
     * @param enabled true to allow end-user replies, false to disallow them
     */
    suspend fun setCommentRepliesEnabled(id: UUID, enabled: Boolean)

    /**
     * Creates a new metadata entry, optionally as a child of a parent collection.
     *
     * @param parent the parent collection to add this metadata to, or null for unparented
     * @param collectionItemAttributes optional JSON attributes for the parent-child relationship
     * @param input the metadata definition to create
     * @return the newly created metadata entry
     */
    suspend fun add(parent: Collection?, collectionItemAttributes: JsonElement?, input: MetadataInput): Metadata

    /**
     * Controls the public visibility of a metadata entry.
     *
     * @param metadata the metadata entry to modify
     * @param public true to make publicly visible, false to restrict access
     */
    suspend fun setPublic(metadata: Metadata, public: Boolean)

    /**
     * Controls the public visibility of a metadata entry's primary content (e.g., the
     * document or data payload).
     *
     * @param metadata the metadata entry to modify
     * @param public true to make content publicly visible, false to restrict access
     */
    suspend fun setPublicContent(metadata: Metadata, public: Boolean)

    /**
     * Controls the public visibility of a metadata entry's supplementary content.
     *
     * @param metadata the metadata entry to modify
     * @param public true to make supplementary content publicly visible, false to restrict access
     */
    suspend fun setPublicSupplementary(metadata: Metadata, public: Boolean)

    /**
     * Grants an access permission on a metadata entry.
     *
     * @param permission the permission definition specifying the entity, group, and action
     * @return the newly created entity permission
     */
    suspend fun addPermission(permission: PermissionInput): EntityPermission

    /**
     * Revokes an access permission from a metadata entry.
     *
     * @param permission the permission definition specifying the entity, group, and action to revoke
     * @return the deleted entity permission
     */
    suspend fun deletePermission(permission: PermissionInput): EntityPermission

    /**
     * Creates a new document-type metadata entry from a template, optionally placing it
     * in a parent collection.
     *
     * @param parentCollectionId the parent collection identifier, or null for unparented
     * @param template the template metadata entry to base the document on
     * @param title optional title override for the new document
     * @param contentType optional content type override
     * @return the newly created metadata entry with its document initialized
     */
    suspend fun addDocument(parentCollectionId: UUID?, template: Metadata, title: String? = null, contentType: String? = null): Metadata

    /**
     * Creates a new data-type metadata entry from a template, optionally placing it
     * in a parent collection.
     *
     * @param parentCollectionId the parent collection identifier, or null for unparented
     * @param template the template metadata entry to base the data on
     * @return the newly created metadata entry with its data initialized
     */
    suspend fun addData(parentCollectionId: UUID?, template: Metadata): Metadata

    /**
     * Creates or replaces the document content for a metadata entry.
     *
     * @param metadata the metadata entry whose document should be set
     * @param document the document content to persist
     * @param collaborationSync how to synchronize the collaboration CRDT row with this
     *  write. Defaults to [CollaborationSyncMode.NONE] so editor saves (which manage
     *  collaboration on their own) are never disturbed.
     */
    suspend fun setDocument(
        metadata: Metadata,
        document: DocumentInput,
        collaborationSync: CollaborationSyncMode = CollaborationSyncMode.NONE,
    )

    /**
     * Assigns a document template to a metadata entry, defining the structure of its
     * document content.
     *
     * @param metadata the metadata entry to modify
     * @param templateId the document template's metadata identifier
     * @param templateVersion the template version to assign
     */
    suspend fun setDocumentTemplate(metadata: Metadata, templateId: UUID, templateVersion: Int)

    /**
     * Assigns a data template to a metadata entry, defining the structure of its
     * data content.
     *
     * @param metadata the metadata entry to modify
     * @param templateId the data template's metadata identifier
     * @param templateVersion the template version to assign
     */
    suspend fun setDataTemplate(metadata: Metadata, templateId: UUID, templateVersion: Int)

    /**
     * Creates or replaces the Bible translation data for a metadata entry.
     *
     * @param metadata the metadata entry whose Bible data should be set
     * @param bible the Bible data to persist
     */
    suspend fun setBible(metadata: Metadata, bible: BibleInput)

    /**
     * Enables or disables a Bible variant and records the metadata update.
     *
     * @param metadata the metadata entry that owns the Bible
     * @param variant the Bible variant identifier
     * @param enabled whether the variant should be available
     * @return the updated Bible variant
     */
    suspend fun setBibleVariantEnabled(metadata: Metadata, variant: String, enabled: Boolean): Bible

    /**
     * Selects the enabled default Bible variant and records the metadata update.
     *
     * @param metadata the metadata entry that owns the Bible
     * @param variant the Bible variant identifier
     * @return the updated default Bible variant
     */
    suspend fun setDefaultBibleVariant(metadata: Metadata, variant: String): Bible

    /**
     * Replaces all user-defined attributes on a metadata entry.
     *
     * @param metadata the metadata entry to modify
     * @param attributes the new attributes JSON to set
     */
    suspend fun setAttributes(metadata: Metadata, attributes: JsonElement)

    /**
     * Deep-merges the provided attributes into the metadata entry's existing attributes.
     *
     * @param metadata the metadata entry to modify
     * @param attributes the attributes JSON to merge
     */
    suspend fun mergeAttributes(metadata: Metadata, attributes: JsonElement)

    /**
     * Creates a new guide-type metadata entry from a template, optionally placing it
     * in a parent collection.
     *
     * @param parentCollectionId the parent collection identifier, or null for unparented
     * @param template the template metadata entry to base the guide on
     * @return the newly created metadata entry with its guide initialized
     */
    suspend fun addGuide(parentCollectionId: UUID?, template: Metadata): Metadata

    /**
     * Adds a new step to an existing guide based on a template step definition.
     *
     * @param guide the guide to add the step to
     * @param templateStepId the template step identifier to use as the blueprint
     * @param index the zero-based position at which to insert the step
     * @return the newly created guide step
     */
    suspend fun addGuideStep(guide: Guide, templateStepId: Long, index: Int): GuideStep

    /**
     * Adds a new module to a guide step based on a template module definition.
     *
     * @param guide the guide containing the target step
     * @param stepId the step identifier to add the module to
     * @param templateModuleId the template module identifier to use as the blueprint
     * @param index the zero-based position at which to insert the module
     * @return the newly created guide step module
     */
    suspend fun addGuideStepModule(guide: Guide, stepId: Long, templateModuleId: Long, index: Int): GuideStepModule

    /**
     * Updates an existing metadata entry with new values.
     *
     * @param id the metadata identifier
     * @param input the updated metadata definition
     * @return the modified metadata entry
     */
    suspend fun edit(id: UUID, input: MetadataInput): Metadata

    /**
     * Records that the primary content file for a metadata entry has been uploaded,
     * storing its content type and size.
     *
     * @param id the metadata identifier
     * @param contentType the MIME type of the uploaded content, or null if unknown
     * @param contentLength the size of the uploaded content in bytes
     */
    suspend fun setUploaded(id: UUID, contentType: String?, contentLength: Long)

    /**
     * Replaces the content's semantic embedding chunks (used by the recommender) in the satellite
     * `metadata_embeddings` table. The replacement is atomic, does not mutate the metadata row, and
     * dispatches no event.
     *
     * The write is skipped when the current metadata row no longer matches the source snapshot, so an
     * older embedding run cannot replace chunks produced from newer content.
     *
     * @param metadata the exact metadata snapshot used to extract the embedded text
     * @param embeddings ordered embedding chunks whose vector lengths must match the
     *   `metadata_embeddings.embedding` column dimension
     * @return true when the chunks were replaced, or false when [metadata] became stale
     */
    suspend fun setEmbeddings(metadata: Metadata, embeddings: List<EmbeddingChunk>): Boolean

    /**
     * Returns the subset of [ids] that already have a stored semantic embedding.
     *
     * @param ids metadata identifiers to inspect
     * @return metadata identifiers with rows in `metadata_embeddings`
     */
    suspend fun getEmbeddingIds(ids: List<UUID>): List<UUID>

    /**
     * Updates the source status for a metadata entry, reflecting the current
     * stage of the content import lifecycle (e.g. pending, importing, imported, failed, external).
     *
     * @param id the metadata identifier
     * @param status the new source status, or null to clear
     */
    suspend fun setSourceStatus(id: UUID, status: SourceStatus?)

    /**
     * Imports the metadata's primary content from an external URL. Marks the entry PENDING and
     * enqueues the platform import job, which downloads the URL, streams it into object storage,
     * resolves the real content type (headers, extension, magic bytes), and marks the entry
     * uploaded/IMPORTED. The download is asynchronous — it runs on the job runner.
     *
     * @param id the metadata identifier whose content should be imported
     * @param url the external URL to download
     * @param contentType the expected content type, preferred when the origin returns a generic one
     * @param ready whether to bring the entry to ready once the import completes
     * @param principalId the principal readiness is attributed to; the system account when null
     */
    suspend fun importFromUrl(id: UUID, url: String, contentType: String? = null, ready: Boolean = false, principalId: UUID? = null)

    /**
     * Records that a supplementary content file for a metadata entry has been uploaded,
     * storing its content type and size.
     *
     * @param id the supplementary content identifier
     * @param contentType the MIME type of the uploaded content, or null if unknown
     * @param contentLength the size of the uploaded content in bytes
     */
    suspend fun setSupplementaryUploaded(id: UUID, contentType: String?, contentLength: Long)

    /**
     * Sets the locked state of a metadata entry at a specific version, preventing or
     * allowing modifications.
     *
     * @param id the metadata identifier
     * @param version the metadata version number
     * @param locked true to lock, false to unlock
     */
    suspend fun setLocked(id: UUID, version: Int, locked: Boolean)

    /**
     * Marks a metadata entry as ready for publishing, recording the principal who approved it.
     *
     * @param metadata the metadata entry to mark as ready
     * @param principal the authenticated principal performing the action
     * @return the updated metadata entry
     */
    suspend fun setReady(metadata: Metadata, principal: Principal): Metadata

    /**
     * Revokes the ready status of a metadata entry, returning it to a non-ready state.
     *
     * @param metadata the metadata entry to mark as not ready
     */
    suspend fun setNotReady(metadata: Metadata)

    /**
     * Assigns a category to a metadata entry.
     *
     * @param id the metadata identifier
     * @param categoryId the category identifier to assign
     */
    suspend fun addCategory(id: UUID, categoryId: UUID)

    /**
     * Removes a category assignment from a metadata entry.
     *
     * @param id the metadata identifier
     * @param categoryId the category identifier to remove
     */
    suspend fun deleteCategory(id: UUID, categoryId: UUID)

    /**
     * Replaces the complete set of categories assigned to a metadata entry.
     *
     * @param id the metadata identifier
     * @param categoryId the new list of category identifiers to assign
     */
    suspend fun setCategories(id: UUID, categoryId: List<UUID>)

    /**
     * Assigns a trait to a metadata entry.
     *
     * @param id the metadata identifier
     * @param traitId the trait identifier to assign
     */
    suspend fun addTrait(id: UUID, traitId: String)

    /**
     * Removes a trait assignment from a metadata entry.
     *
     * @param id the metadata identifier
     * @param traitId the trait identifier to remove
     */
    suspend fun deleteTrait(id: UUID, traitId: String)

    /**
     * Replaces the complete set of traits assigned to a metadata entry.
     *
     * @param id the metadata identifier
     * @param traitIds the new list of trait identifiers to assign
     */
    suspend fun setTraits(id: UUID, traitIds: List<String>)

    /**
     * Clears the uploaded content status for a metadata entry, indicating that its
     * primary content is no longer present.
     *
     * @param id the metadata identifier
     */
    suspend fun clearUploaded(id: UUID)

    /**
     * Soft-deletes a metadata entry by marking it as deleted without removing it from storage.
     *
     * @param id the metadata identifier to mark as deleted
     */
    suspend fun markDeleted(id: UUID)

    /**
     * Deletes the guide content from a metadata entry, removing the guide and all its
     * steps and modules.
     *
     * @param metadata the metadata entry whose guide should be deleted
     */
    suspend fun deleteGuide(metadata: Metadata)

    /**
     * Deletes a specific step and its modules from a metadata entry's guide.
     *
     * @param metadata the metadata entry containing the guide
     * @param stepId the step identifier to delete
     */
    suspend fun deleteGuideStep(metadata: Metadata, stepId: Long)

    /**
     * Deletes a specific module from a step in a metadata entry's guide.
     *
     * @param metadata the metadata entry containing the guide
     * @param stepId the step identifier containing the module
     * @param moduleId the module identifier to delete
     */
    suspend fun deleteGuideStepModule(metadata: Metadata, stepId: Long, moduleId: Long)

    /**
     * Permanently removes a metadata entry and all associated data from storage.
     *
     * @param metadata the metadata entry to permanently delete
     */
    suspend fun delete(metadata: Metadata)

    /**
     * Replaces all system-managed attributes on a metadata entry. System attributes are
     * distinct from user-defined attributes and are typically managed by internal processes.
     *
     * @param metadata the metadata entry to modify
     * @param attributes the new system attributes JSON, or null to clear
     */
    suspend fun setSystemAttributes(
        metadata: Metadata,
        attributes: JsonElement?
    )

    /**
     * Retrieves all supplementary content entries attached to a metadata entry.
     *
     * @param metadataId the metadata identifier
     * @return the list of supplementary content entries
     */
    suspend fun getSupplementary(metadataId: UUID): List<MetadataSupplementary>

    /**
     * Registers a batch loader for efficiently fetching supplementary content by metadata
     * cache key.
     *
     * @param batch the batch accumulator to populate with supplementary content lists
     */
    suspend fun addSupplementaryToBatch(batch: Batch<MetadataCacheKeyId, List<MetadataSupplementary>>)

    /**
     * Looks up a supplementary content entry by its identifier.
     *
     * @param id the supplementary content identifier
     * @return the supplementary content entry, or null if not found
     */
    suspend fun getSupplementaryById(id: UUID): MetadataSupplementary?

    /**
     * Looks up a supplementary content entry by its parent metadata identifier and key.
     *
     * @param id the parent metadata identifier
     * @param key the supplementary content key
     * @return the supplementary content entry, or null if not found
     */
    suspend fun getSupplementaryByMetadataAndKey(id: UUID, key: String): MetadataSupplementary?

    /**
     * Attaches a new supplementary content entry to a metadata entry.
     *
     * @param supplementary the supplementary content definition to create
     * @return the newly created supplementary content entry
     */
    suspend fun addSupplementary(supplementary: MetadataSupplementaryInput): MetadataSupplementary

    /**
     * Replaces the content of a supplementary entry with a string value.
     *
     * @param metadata the parent metadata entry
     * @param id the supplementary content identifier
     * @param content the new string content
     * @param contentType the MIME type of the content
     */
    suspend fun updateSupplementaryContent(
        metadata: Metadata,
        id: UUID,
        content: String,
        contentType: String
    )

    /**
     * Replaces the content of a supplementary entry with an uploaded file.
     *
     * @param metadata the parent metadata entry
     * @param id the supplementary content identifier
     * @param file the uploaded file part
     * @param contentType the MIME type of the content
     */
    suspend fun updateSupplementaryContent(
        metadata: Metadata,
        id: UUID,
        file: PartData.FileItem,
        contentType: String
    )

    /**
     * Records that a supplementary content file has been fully uploaded, storing its
     * size and content type.
     *
     * @param metadata the parent metadata entry
     * @param id the supplementary content identifier
     * @param length the size of the uploaded content in bytes
     * @param contentType the MIME type of the uploaded content
     */
    suspend fun setSupplementaryUploaded(
        metadata: Metadata,
        id: UUID,
        length: Long,
        contentType: String
    )

    /**
     * Retrieves all relationships from a metadata entry to other metadata entries.
     *
     * @param id the metadata identifier
     * @return the list of metadata relationships
     */
    suspend fun getRelationships(id: UUID): List<MetadataRelationship>

    /**
     * Registers a batch loader for efficiently fetching metadata relationships by
     * metadata cache key.
     *
     * @param batch the batch accumulator to populate with relationship lists
     */
    suspend fun addRelationshipsToBatch(batch: Batch<MetadataCacheKeyId, List<MetadataRelationship>>)

    /**
     * Creates a new relationship between two metadata entries from an input definition.
     *
     * @param relationship the relationship input defining the two metadata entries and type
     * @return the newly created metadata relationship
     */
    suspend fun addRelationship(relationship: MetadataRelationshipInput): MetadataRelationship

    /**
     * Creates a new relationship between two metadata entries from an existing relationship
     * object.
     *
     * @param relationship the relationship to persist
     * @return the persisted metadata relationship
     */
    suspend fun addRelationship(relationship: MetadataRelationship): MetadataRelationship

    /**
     * Deep-merges attributes into an existing relationship between two metadata entries.
     *
     * @param id1 the first metadata identifier in the relationship
     * @param id2 the second metadata identifier in the relationship
     * @param relationship the relationship type name
     * @param attributes the attributes JSON to merge
     */
    suspend fun mergeAttributes(id1: UUID, id2: UUID, relationship: String, attributes: JsonElement)

    /**
     * Removes a relationship between two metadata entries.
     *
     * @param id1 the first metadata identifier in the relationship
     * @param id2 the second metadata identifier in the relationship
     * @param relationship the relationship type name
     */
    suspend fun removeRelationship(id1: UUID, id2: UUID, relationship: String)

    /**
     * Retrieves all parent collections that contain this metadata entry.
     *
     * @param id the metadata identifier
     * @return the list of parent collections
     */
    suspend fun getParents(id: UUID): List<Collection>

    /**
     * Retrieves parent collections that contain this metadata entry, with pagination.
     *
     * @param id the metadata identifier
     * @param offset the zero-based offset for pagination
     * @param limit the maximum number of parent collections to return
     * @return the paginated list of parent collections
     */
    suspend fun getParents(id: UUID, offset: Long, limit: Int): List<Collection>

    /**
     * Searches for metadata entries matching the given query criteria, applying the
     * current user's permission context.
     *
     * @param input the search query parameters
     * @return the list of matching metadata entries
     */
    suspend fun find(input: FindQueryInput): List<Metadata>

    /**
     * Searches for metadata entries matching the given query criteria using system-level
     * permissions (bypassing user-level access control).
     *
     * @param input the search query parameters
     * @return the list of matching metadata entries
     */
    suspend fun findBySystem(input: FindQueryInput): List<Metadata>

    /**
     * Returns the total count of metadata entries matching the given query criteria.
     *
     * @param input the search query parameters
     * @return the total count of matching entries
     */
    suspend fun findCount(input: FindQueryInput): Long

    /**
     * Retrieves the workflow execution plans for a metadata entry.
     *
     * @param id the metadata identifier
     * @return the list of workflow plans associated with this metadata
     */
    suspend fun getPlans(id: UUID): List<MetadataWorkflowPlan>

    /**
     * Retrieves every Bible variant stored for a metadata entry at a specific version, including
     * disabled variants. API callers must authorize requests that expose disabled variants.
     *
     * @param id the metadata identifier
     * @param version the metadata version number
     * @return all stored Bible variants
     */
    suspend fun getBibles(id: UUID, version: Int): List<Bible>

    /**
     * Retrieves the Bible translation data associated with a metadata entry at a
     * specific version.
     *
     * @param id the metadata identifier
     * @param version the metadata version number
     * @param variant an optional variant identifier
     * @return the Bible data, or null if not found
     */
    suspend fun getBible(id: UUID, version: Int, variant: String?): Bible?

    /**
     * Removes a supplementary content entry from a metadata entry and deletes its
     * stored content.
     *
     * @param metadata the parent metadata entry
     * @param id the supplementary content identifier to delete
     */
    suspend fun deleteSupplementary(metadata: Metadata, id: UUID)

    /**
     * Detaches a supplementary content entry from a metadata entry without deleting
     * its stored content.
     *
     * @param metadata the parent metadata entry
     * @param id the supplementary content identifier to detach
     */
    suspend fun detachSupplementary(metadata: Metadata, id: UUID)

    /**
     * Marks the collaboration collections data as dirty for a metadata entry, triggering
     * re-synchronization of collaborative collection data.
     *
     * @param metadataId the metadata identifier
     */
    suspend fun markCollaborationCollectionsDirty(metadataId: UUID)

    /**
     * Marks the collaboration relationships data as dirty for a metadata entry, triggering
     * re-synchronization of collaborative relationship data.
     *
     * @param metadataId the metadata identifier
     */
    suspend fun markCollaborationRelationshipsDirty(metadataId: UUID)

    /**
     * Marks the collaboration attributes data as dirty for a metadata entry, triggering
     * re-synchronization of collaborative attribute data.
     *
     * @param metadataId the metadata identifier
     */
    suspend fun markCollaborationAttributesDirty(metadataId: UUID)
}
