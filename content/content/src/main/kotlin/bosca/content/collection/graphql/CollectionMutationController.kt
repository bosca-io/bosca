package bosca.content.collection.graphql

import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionInput
import bosca.content.collection.model.CollectionLanguageVariantInput
import bosca.content.collection.model.CollectionMetadataRelationship
import bosca.content.collection.model.CollectionMetadataRelationshipInput
import bosca.content.collection.model.CollectionParentCollection
import bosca.content.collection.model.CollectionSupplementaryInput
import bosca.content.collection.model.CollectionWorkflowCompleteState
import bosca.content.collection.model.CollectionWorkflowState
import bosca.content.collection.service.CollectionService
import bosca.content.configuration.MAX_BULK_OPERATION_SIZE
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentRelationship
import bosca.content.ordering.OrderingInput
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.transaction
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Permission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.graphql.scalars.UploadedFile
import bosca.server.content.PartData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.NoSuchElementException

object CollectionMutation

@TypeController
class CollectionMutationController(
    private val service: CollectionService,
    private val permissionEvaluator: CollectionPermissionEvaluator,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
    private val slugService: SlugService,
    private val json: Json,
) : GraphQLController<CollectionMutation> {

    @Field
    suspend fun setParentCollections(
        authentication: AuthenticationContext,
        id: UUID,
        collections: List<CollectionParentCollection>
    ): Boolean = transaction {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        val parents = service.getCollectionParents(id)
        val newIds = collections.mapTo(mutableSetOf()) { it.id }
        val oldIds = parents.mapTo(mutableSetOf()) { it.id }
        for (parent in parents.filter { it.id !in newIds }) {
            val parentCollection = service.getById(parent.id)
                ?: throw NoSuchElementException("Collection not found: ${parent.id}")
            permissionEvaluator.verifyAllowed(authentication, parentCollection, PermissionAction.EDIT)
            service.removeCollectionItem(parent.id, id)
        }
        for (parentInput in collections) {
            val parentCollection = service.getById(parentInput.id)
                ?: throw NoSuchElementException("Collection not found: ${parentInput.id}")
            permissionEvaluator.verifyAllowed(authentication, parentCollection, PermissionAction.EDIT)
            if (parentInput.id in oldIds) {
                parentInput.attributes?.let {
                    service.mergeCollectionItemAttributes(parentInput.id, id, it)
                }
            } else {
                service.addCollectionItem(parentInput.id, id, parentInput.attributes)
            }
        }
        true
    }

    @Field
    suspend fun add(
        authentication: AuthenticationContext,
        collection: CollectionInput,
        collectionItemAttributes: JsonElement?,
        setReady: Boolean?
    ): Collection {
        val parent = collection.parentCollectionId?.let {
            service.getById(it)
        }
        if (parent != null) {
            permissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
        } else {
            groupEvaluator.verifyHasEditorGroup(authentication)
        }
        val newCollection = service.add(collection, parent, collectionItemAttributes)
        if (setReady == true) {
            service.setReady(newCollection, authentication.principal()?.asPrincipal() ?: error("No principal found"))
        }
        return newCollection
    }

    @Field
    suspend fun addLanguageVariant(
        authentication: AuthenticationContext,
        variant: CollectionLanguageVariantInput,
        setReady: Boolean?
    ): Collection {
        val collection = service.getById(variant.id) ?: throw NoSuchElementException("Collection not found: ${variant.id}")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        val addedVariant = service.addLanguageVariant(variant)
        if (setReady == true) {
            service.setReady(addedVariant, authentication.principal()?.asPrincipal() ?: error("No principal found"))
        }
        return collection
    }

    @Field
    suspend fun editLanguageVariant(authentication: AuthenticationContext, variant: CollectionLanguageVariantInput): Collection {
        val collection = service.getById(variant.id) ?: throw NoSuchElementException("Collection not found: ${variant.id}")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.editLanguageVariant(variant)
        return collection
    }

    @Field
    suspend fun deleteLanguageVariant(authentication: AuthenticationContext, id: UUID, languageTag: String): Collection {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.DELETE)
        service.deleteLanguageVariant(id, languageTag)
        return collection
    }

    @Field
    suspend fun addChildCollection(
        authentication: AuthenticationContext,
        id: UUID,
        collectionId: UUID,
        attributes: JsonElement?
    ): Collection? {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $collectionId")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.addCollectionItem(id, collectionId, attributes)
        return service.getById(id)
    }

    @Field
    suspend fun addChildMetadata(
        authentication: AuthenticationContext,
        id: UUID,
        metadataId: UUID,
        attributes: JsonElement?
    ): Collection? {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.addMetadataItem(id, metadataId, attributes)
        return service.getById(id)
    }

    @Field
    suspend fun setCollectionSearchable(
        authentication: AuthenticationContext,
        id: UUID,
        searchable: Boolean,
        languageTag: String? = null,
    ): Boolean {
        val collection = service.getById(id)
            ?: throw NoSuchElementException("Collection not found: $id")
        if (collection.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.setSearchable(id, searchable, languageTag)
        return true
    }

    @Field
    suspend fun setCollectionRecommendable(
        authentication: AuthenticationContext,
        id: UUID,
        recommendable: Boolean,
        languageTag: String? = null,
    ): Boolean {
        val collection = service.getById(id)
            ?: throw NoSuchElementException("Collection not found: $id")
        if (collection.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.setRecommendable(id, recommendable, languageTag)
        return true
    }

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        id: UUID,
        collection: CollectionInput
    ): Collection {
        val current = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        permissionEvaluator.verifyAllowed(authentication, current, PermissionAction.EDIT)
        return service.edit(id, collection)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID, recursive: Boolean?) = transaction {
        val current = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        permissionEvaluator.verifyAllowed(authentication, current, PermissionAction.DELETE)
        if (recursive == true) {
//            deleteRecursive(authentication, id)
            TODO()
        } else {
            service.markDeleted(id)
        }
        true
    }

    /**
     * Soft-delete multiple collections in a single operation, skipping items
     * the caller lacks permission to delete.
     */
    @Field
    suspend fun deleteAll(authentication: AuthenticationContext, collectionIds: List<UUID>): Int {
        require(collectionIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        return transaction {
            val collections = service.getByIds(collectionIds)
            val allowed = permissionEvaluator.filterAllowed(authentication, collections, PermissionAction.DELETE)
            for (collection in allowed) {
                service.markDeleted(collection.id)
            }
            allowed.size
        }
    }

    /**
     * Set visibility, search eligibility, and optional recommendation eligibility for multiple
     * collections in a single operation, skipping locked items (unless caller is SA)
     * and items the caller lacks permission to edit.
     */
    @Field
    suspend fun setPublicAll(
        authentication: AuthenticationContext,
        collectionIds: List<UUID>,
        public: Boolean,
        publicList: Boolean,
        publicSupplementary: Boolean,
        searchable: Boolean,
        recommendable: Boolean? = null,
    ): Int {
        require(collectionIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        return transaction {
            val isSa = groupEvaluator.hasSaGroup(authentication)
            val collections = service.getByIds(collectionIds)
            val unlocked = if (isSa) collections else collections.filter { !it.locked }
            val allowed = permissionEvaluator.filterAllowed(authentication, unlocked, PermissionAction.EDIT)
            for (collection in allowed) {
                service.setPublic(collection.id, public)
                service.setPublicList(collection.id, publicList)
                service.setPublicSupplementary(collection.id, publicSupplementary)
                service.setSearchable(collection.id, searchable)
                recommendable?.let { service.setRecommendable(collection.id, it) }
            }
            allowed.size
        }
    }

    /**
     * Set recommendation eligibility for multiple collections in a single operation,
     * skipping locked items (unless caller is SA) and items the caller cannot edit.
     */
    @Field
    suspend fun setRecommendableAll(
        authentication: AuthenticationContext,
        collectionIds: List<UUID>,
        recommendable: Boolean,
    ): Int {
        require(collectionIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        return transaction {
            val isSa = groupEvaluator.hasSaGroup(authentication)
            val collections = service.getByIds(collectionIds)
            val unlocked = if (isSa) collections else collections.filter { !it.locked }
            val allowed = permissionEvaluator.filterAllowed(authentication, unlocked, PermissionAction.EDIT)
            for (collection in allowed) {
                service.setRecommendable(collection.id, recommendable)
            }
            allowed.size
        }
    }

    /**
     * Set the workflow state for multiple collections in a single operation,
     * skipping items already in the target state or that the caller lacks
     * permission to edit.
     */
    @Field
    suspend fun setWorkflowStateAll(
        authentication: AuthenticationContext,
        collectionIds: List<UUID>,
        stateId: String,
        status: String
    ): Int {
        require(collectionIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        return transaction {
            val principal = authentication.principal() ?: return@transaction 0
            val collections = service.getByIds(collectionIds)
            val candidates = collections.filter { it.workflowStateId != stateId }
            val allowed = permissionEvaluator.filterAllowed(authentication, candidates, PermissionAction.EXECUTE)
            for (collection in allowed) {
                service.setState(
                    principal = principal.asPrincipal(),
                    item = collection,
                    toStateId = stateId,
                    status = status
                )
            }
            allowed.size
        }
    }

    private suspend fun deleteMetadata(authentication: AuthenticationContext, id: UUID) {
        val metadata = metadataService.getById(id) ?: return
        if (metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.DELETE)) {
            metadataService.markDeleted(id)
        }
    }

    private suspend fun deleteRecursive(authentication: AuthenticationContext, id: UUID) {
        val current = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        if (!permissionEvaluator.isAllowed(authentication, current, PermissionAction.DELETE)) {
            return
        }
        var offset = 0L
        do {
            val items = service.getItemsNoCache(id, offset, 100)
            if (items.isEmpty()) break
            for (item in items) {
                item.childCollectionId?.let { deleteRecursive(authentication, it) }
                item.childMetadataId?.let { deleteMetadata(authentication, it) }
            }
            offset += 100
        } while (true)
        service.markDeleted(id)
    }

    @Field
    suspend fun addMetadataRelationship(
        authentication: AuthenticationContext,
        relationship: CollectionMetadataRelationshipInput
    ): ContentRelationship {
        val collection = service.getById(relationship.id) ?: throw NoSuchElementException("Collection not found: ${relationship.id}")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        return service.addMetadataRelationship(relationship)
    }

    @Field
    suspend fun editMetadataRelationship(
        authentication: AuthenticationContext,
        relationship: CollectionMetadataRelationshipInput
    ): Boolean {
        val collection = service.getById(relationship.id) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.editMetadataRelationship(relationship)
        return true
    }

    @Field
    suspend fun deleteMetadataRelationship(
        authentication: AuthenticationContext,
        id: UUID,
        metadataId: UUID,
        relationship: String,
        languageTag: String? = null
    ): Boolean {
        val collection = service.getById(id) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        if (languageTag != null) {
            service.deleteMetadataRelationship(id, languageTag, metadataId, relationship)
        } else {
            service.deleteMetadataRelationship(id, metadataId, relationship)
        }
        return true
    }

    @Field
    suspend fun mergeMetadataRelationshipAttributes(
        authentication: AuthenticationContext,
        attributes: JsonElement,
        collectionId: UUID,
        metadataId: UUID,
        relationship: String,
        languageTag: String? = null
    ): Boolean {
        val collection = service.getById(collectionId) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        if (languageTag != null) {
            service.mergeMetadataRelationshipAttributes(collectionId, languageTag, metadataId, relationship, attributes)
        } else {
            service.mergeMetadataRelationshipAttributes(collectionId, metadataId, relationship, attributes)
        }
        return true
    }

    // Attribute management mutations
    @Field
    suspend fun setCollectionAttributes(
        authentication: AuthenticationContext,
        id: UUID,
        attributes: JsonElement
    ): Boolean {
        val collection = service.getById(id) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.setAttributes(id, attributes)
        return true
    }

    @Field
    suspend fun mergeCollectionAttributes(
        authentication: AuthenticationContext,
        id: UUID,
        attributes: JsonElement,
    ): Boolean {
        val collection = service.getById(id) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.mergeAttributes(id, attributes)
        return true
    }

    @Field
    suspend fun setCollectionSystemAttributes(
        authentication: AuthenticationContext,
        id: UUID,
        attributes: JsonElement
    ): Boolean {
        val collection = service.getById(id) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.setSystemAttributes(id, attributes)
        return true
    }

    @Field
    suspend fun mergeCollectionItemAttributes(
        authentication: AuthenticationContext,
        attributes: JsonElement,
        id: UUID,
        itemId: UUID
    ): Boolean {
        val collection = service.getById(id) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.mergeCollectionItemAttributes(id, itemId, attributes)
        return true
    }

    @Field
    suspend fun mergeMetadataItemAttributes(
        authentication: AuthenticationContext,
        attributes: JsonElement,
        id: UUID,
        itemId: UUID
    ): Boolean {
        val collection = service.getById(id) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.mergeMetadataItemAttributes(id, itemId, attributes)
        return true
    }

    @Field
    suspend fun setLocked(
        authentication: AuthenticationContext,
        id: UUID,
        locked: Boolean
    ): Collection? {
        val collection = service.getById(id) ?: return null
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.setLocked(collection, locked)
        return collection.copy(locked = locked)
    }

    @Field
    suspend fun setPublic(
        authentication: AuthenticationContext,
        id: UUID,
        public: Boolean,
        languageTag: String? = null
    ): Collection {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        if (languageTag != null) {
            val variant = service.getLanguageVariant(id, languageTag) ?: throw NoSuchElementException("Variant not found: $id/$languageTag")
            permissionEvaluator.verifyAllowed(authentication, variant, PermissionAction.EDIT)
        } else {
            permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        }
        service.setPublic(id, public, languageTag)
        return collection.copy(public = if (languageTag == null) public else collection.public)
    }

    @Field
    suspend fun setPublicList(
        authentication: AuthenticationContext,
        id: UUID,
        public: Boolean,
        languageTag: String? = null
    ): Collection {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        if (languageTag != null) {
            val variant = service.getLanguageVariant(id, languageTag) ?: throw NoSuchElementException("Variant not found: $id/$languageTag")
            permissionEvaluator.verifyAllowed(authentication, variant, PermissionAction.EDIT)
        } else {
            permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        }
        service.setPublicList(id, public, languageTag)
        return collection.copy(publicList = if (languageTag == null) public else collection.publicList)
    }

    @Field
    suspend fun setPublicSupplementary(
        authentication: AuthenticationContext,
        id: UUID,
        public: Boolean,
        languageTag: String? = null
    ): Collection {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        if (languageTag != null) {
            val variant = service.getLanguageVariant(id, languageTag) ?: throw NoSuchElementException("Variant not found: $id/$languageTag")
            permissionEvaluator.verifyAllowed(authentication, variant, PermissionAction.EDIT)
        } else {
            permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        }
        service.setPublicSupplementary(id, public, languageTag)
        return collection.copy(publicSupplementary = if (languageTag == null) public else collection.publicSupplementary)
    }

    @Field
    suspend fun setNotReady(
        authentication: AuthenticationContext,
        id: UUID,
        languageTag: String? = null
    ): Boolean {
        val collection = if (languageTag == null) {
            service.getById(id) ?: return false
        } else {
            service.getLanguageVariant(id, languageTag) ?: return false
        }
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.setNotReady(collection)
        return true
    }

    @Field
    suspend fun setReady(
        authentication: AuthenticationContext,
        id: UUID,
        languageTag: String? = null
    ): Boolean {
        val collection = if (languageTag == null) {
            service.getById(id) ?: return false
        } else {
            service.getLanguageVariant(id, languageTag) ?: return false
        }
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.setReady(id, authentication.principal()?.asPrincipal() ?: error("missing principal"), languageTag)
        return true
    }

    /**
     * Mark multiple collections as ready in a single operation, skipping locked items
     * (unless caller is SA) and items the caller lacks permission to edit.
     * Returns the number of collections successfully marked as ready.
     */
    @Field
    suspend fun setReadyAll(
        authentication: AuthenticationContext,
        collectionIds: List<UUID>
    ): Int {
        require(collectionIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        return transaction {
            val isSa = groupEvaluator.hasSaGroup(authentication)
            val principal = authentication.principal()?.asPrincipal() ?: error("missing principal")
            val collections = service.getByIds(collectionIds)
            val unlocked = if (isSa) collections else collections.filter { !it.locked }
            val allowed = permissionEvaluator.filterAllowed(authentication, unlocked, PermissionAction.EDIT)
            for (collection in allowed) {
                service.setReady(collection.id, principal, null)
            }
            allowed.size
        }
    }

    @Field
    suspend fun setCollectionSlug(
        authentication: AuthenticationContext,
        id: UUID,
        slug: String,
        languageTag: String? = null
    ): String = transaction {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        slugService.deleteCollectionSlug(id, languageTag)
        if (slugService.get(slug) != null) error("Slug already exists")
        slugService.add(Slug(slug = slug, collectionId = id, languageTag = languageTag)).slug
    }

    @Field
    suspend fun addPermission(
        authentication: AuthenticationContext,
        permission: PermissionInput
    ): Permission {
        val collection = service.getById(permission.entityId)
            ?: throw NoSuchElementException("Collection not found: ${permission.entityId}")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.MANAGE)
        service.addPermission(permission.entityId, permission.groupId, permission.action)
        return Permission(
            groupId = permission.groupId,
            action = permission.action
        )
    }

    @Field
    suspend fun deletePermission(
        authentication: AuthenticationContext,
        permission: PermissionInput
    ): Permission {
        val collection = service.getById(permission.entityId)
            ?: throw NoSuchElementException("Collection not found: ${permission.entityId}")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.MANAGE)
        service.deletePermission(permission.entityId, permission.groupId, permission.action)
        return Permission(
            groupId = permission.groupId,
            action = permission.action
        )
    }

    // Supplementary content mutations
    @Field
    suspend fun addSupplementary(
        authentication: AuthenticationContext,
        supplementary: CollectionSupplementaryInput
    ): CollectionSupplementaryContext {
        val collection = service.getById(supplementary.collectionId)
            ?: throw NoSuchElementException("Collection not found: ${supplementary.collectionId}")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        return CollectionSupplementaryContext(collection, service.addSupplementary(supplementary))
    }

    @Field
    suspend fun deleteSupplementary(
        authentication: AuthenticationContext,
        id: UUID
    ): Boolean {
        val supplementary =
            service.getSupplementaryById(id) ?: throw NoSuchElementException("Supplementary item not found: $id")
        val collection = service.getById(supplementary.collectionId)
            ?: throw NoSuchElementException("Collection not found: ${supplementary.collectionId}")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.deleteSupplementary(collection, id)
        return true
    }

    @Field
    suspend fun setSupplementaryContents(
        authentication: AuthenticationContext,
        contentType: String,
        file: UploadedFile,
        supplementaryId: UUID
    ): Boolean {
        val supplementaryItem = service.getSupplementaryById(supplementaryId)
            ?: throw NoSuchElementException("Supplementary item not found: $supplementaryId")
        val collection = service.getById(supplementaryItem.collectionId)
            ?: throw NoSuchElementException("Collection not found: ${supplementaryItem.collectionId}")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        val principal = authentication.principal() ?: return false
        val fileItem = PartData.UploadedFileItem(file)
        try {
            service.updateSupplementaryContent(principal.asPrincipal(), collection, supplementaryId, fileItem, contentType)
        } finally {
            fileItem.dispose()
        }
        return true
    }

    @Field
    suspend fun setSupplementaryTextContents(
        authentication: AuthenticationContext,
        supplementaryId: UUID,
        content: String,
        contentType: String,
    ): Boolean {
        val supplementaryItem = service.getSupplementaryById(supplementaryId)
            ?: throw NoSuchElementException("Supplementary item not found: $supplementaryId")
        val collection = service.getById(supplementaryItem.collectionId)
            ?: throw NoSuchElementException("Collection not found: ${supplementaryItem.collectionId}")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        val principal = authentication.principal() ?: return false
        service.updateSupplementaryContent(principal.asPrincipal(), collection, supplementaryId, content, contentType)
        return true
    }

    @Field
    suspend fun setSupplementaryUploaded(
        authentication: AuthenticationContext,
        supplementaryId: UUID,
        contentType: String,
        len: Int
    ): Boolean {
        val supplementaryItem = service.getSupplementaryById(supplementaryId)
            ?: throw NoSuchElementException("Supplementary item not found: $supplementaryId")
        val collection = service.getById(supplementaryItem.collectionId)
            ?: throw NoSuchElementException("Collection not found: ${supplementaryItem.collectionId}")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.markSupplementaryUploaded(supplementaryId, contentType, len)
        return true
    }

    @Field
    suspend fun removeChildCollection(
        authentication: AuthenticationContext,
        id: UUID,
        collectionId: UUID
    ): Collection {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.removeCollectionItem(id, collectionId)
        return collection
    }

    @Field
    suspend fun removeChildMetadata(
        authentication: AuthenticationContext,
        id: UUID,
        metadataId: UUID
    ): Collection {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.removeMetadataItem(id, metadataId)
        return collection
    }

    @Field
    suspend fun permanentlyDelete(
        authentication: AuthenticationContext,
        collectionId: UUID
    ): Boolean {
        val collection = service.getById(collectionId) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.DELETE)
        service.permanentlyDelete(collectionId)
        return true
    }

    @Field
    suspend fun setChildItemAttributes(
        authentication: AuthenticationContext,
        id: UUID,
        attributes: JsonElement?,
        childCollectionId: UUID?,
        childMetadataId: UUID?,
    ): Collection {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        childCollectionId?.let {
            service.setCollectionItemAttributes(id, it, attributes)
        }
        childMetadataId?.let {
            service.setMetadataItemAttributes(id, it, attributes)
        }
        return collection
    }

    @Field
    suspend fun setCategories(
        authentication: AuthenticationContext,
        id: UUID,
        categoryIds: List<UUID>,
    ): Boolean {
        val collection = service.getById(id) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.setCategories(id, categoryIds)
        return true
    }

    @Field
    suspend fun setTemplate(
        authentication: AuthenticationContext,
        collectionId: UUID,
        templateId: UUID,
        templateVersion: Int
    ): Boolean {
        val collection = service.getById(collectionId) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.setTemplate(collectionId, templateId, templateVersion)
        return true
    }

    @Field
    suspend fun setCollectionOrdering(
        authentication: AuthenticationContext,
        id: UUID,
        ordering: List<OrderingInput>
    ): Boolean {
        val collection = service.getById(id) ?: return false
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.setCollectionOrdering(id, json.encodeToJsonElement(ListSerializer(OrderingInput.serializer()), ordering))
        return true
    }

    @Field
    suspend fun setWorkflowState(
        authentication: AuthenticationContext,
        state: CollectionWorkflowState
    ): Boolean {
        val principal = authentication.principal() ?: return false
        val collection = service.getById(state.collectionId) ?: return false
        groupEvaluator.verifyHasSaGroup(authentication)
        if (state.immediate) {
            service.setState(
                principal = principal.asPrincipal(),
                item = collection,
                toStateId = state.stateId,
                status = state.status,
            )
        } else {
            service.setPendingState(
                principal = principal.asPrincipal(),
                item = collection,
                toStateId = state.stateId,
                status = state.status,
            )
        }
        return true
    }

    @Field
    suspend fun setWorkflowStateComplete(
        authentication: AuthenticationContext,
        state: CollectionWorkflowCompleteState
    ): Boolean {
        val principal = authentication.principal() ?: return false
        val collection = service.getById(state.collectionId) ?: return false
        groupEvaluator.verifyHasSaGroup(authentication)
        service.setPendingStateComplete(
            item = collection,
            status = state.status,
            principal = principal.asPrincipal()
        )
        return true
    }

    @Field
    suspend fun setMetadataRelationships(
        authentication: AuthenticationContext,
        id: UUID,
        relationships: List<CollectionMetadataRelationshipInput>,
        languageTag: String? = null
    ): Boolean = transaction {
        val collection = service.getById(id) ?: throw NoSuchElementException("Collection not found: $id")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        val current = if (languageTag != null) {
            service.getMetadataRelationships(id, languageTag)
        } else {
            service.getMetadataRelationships(id)
        }
        val newMap = relationships.associateBy { Triple(it.id, it.metadataId, it.relationship) }
        val oldMap = current.associateBy { Triple(it.id1, it.id2, it.relationship) }

        for (relationship in current.filter { Triple(it.id1, it.id2, it.relationship) !in newMap.keys }) {
            if (languageTag == null) {
                service.deleteMetadataRelationship(relationship.id1, relationship.id2, relationship.relationship)
            } else {
                service.deleteMetadataRelationship(relationship.id1, languageTag, relationship.id2, relationship.relationship)
            }
        }
        for (relationship in relationships) {
            if (relationship.id != id) throw IllegalArgumentException("Invalid relationship: $relationship")
            val key = Triple(relationship.id, relationship.metadataId, relationship.relationship)
            if (key in oldMap.keys) {
                relationship.attributes?.let {
                    if (languageTag == null) {
                        service.mergeMetadataRelationshipAttributes(relationship.id, relationship.metadataId, relationship.relationship ?: "", it)
                    } else {
                        service.mergeMetadataRelationshipAttributes(relationship.id, languageTag, relationship.metadataId, relationship.relationship ?: "", it)
                    }
                }
            } else {
                if (languageTag == null) {
                    service.addMetadataRelationship(relationship.copy(languageTag = null))
                } else {
                    service.addMetadataRelationship(relationship.copy(languageTag = languageTag))
                }
            }
        }
        true
    }

    @Field
    suspend fun syncVariants(
        authentication: AuthenticationContext,
        collectionId: UUID,
    ): Boolean {
        val collection = service.getById(collectionId) ?: throw NoSuchElementException("Collection not found: $collectionId")
        permissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
        service.syncVariantItems(collectionId)
        return true
    }
}
