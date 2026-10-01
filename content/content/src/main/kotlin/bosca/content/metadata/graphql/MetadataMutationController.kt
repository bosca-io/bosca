package bosca.content.metadata.graphql

import org.slf4j.LoggerFactory
import bosca.attributes.TemplateAttributeInput
import bosca.attributes.TemplateToolInput
import bosca.attributes.TemplateWorkflowInput
import bosca.content.collection.model.CollectionTemplateMutation
import bosca.content.configuration.MAX_BULK_OPERATION_SIZE
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.model.BibleInput
import bosca.content.metadata.model.CollectionTemplateFilters
import bosca.content.metadata.model.CollectionTemplateInput
import bosca.content.metadata.model.ContainerRendererInput
import bosca.content.metadata.model.DataInput
import bosca.content.metadata.model.DataTemplateInput
import bosca.content.metadata.model.DataTemplateMutation
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.DocumentTemplateContainerInput
import bosca.content.metadata.model.DocumentTemplateInput
import bosca.content.metadata.model.DocumentTemplateMutation
import bosca.content.metadata.model.GuideInput
import bosca.content.metadata.model.GuideStepContext
import bosca.content.metadata.model.GuideStepInput
import bosca.content.metadata.model.GuideStepModule
import bosca.content.metadata.model.GuideStepModuleInput
import bosca.content.metadata.model.GuideTemplateInput
import bosca.content.metadata.model.GuideTemplateStepInput
import bosca.content.metadata.model.GuideTemplateStepModuleInput
import bosca.content.metadata.model.MediaProcessingOptions
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataParentCollection
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.content.metadata.model.MetadataSourceInput
import bosca.content.metadata.model.SourceStatus
import bosca.source.service.SourceService
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.model.MetadataWorkflowCompleteState
import bosca.content.metadata.model.MetadataWorkflowState
import bosca.content.metadata.events.MetadataUpdated
import bosca.content.metadata.events.dispatch
import bosca.content.video.service.VideoService
import bosca.content.metadata.service.CollaborationSyncMode
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MetadataService
import bosca.content.ordering.OrderingInput
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.db.transaction
import bosca.jobs.BibleProcessJob
import bosca.jobs.ImportUrlJob
import bosca.jobs.enqueue
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.model.PermissionInput
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.JsonConverter.toJsonElement
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.download
import bosca.storage.service.upload
import bosca.graphql.scalars.UploadedFile
import bosca.server.content.PartData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement
import java.text.SimpleDateFormat
import java.util.*

object MetadataMutation

@TypeController
class MetadataMutationController(
    private val service: MetadataService,
    private val collectionService: CollectionService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
    private val storage: ObjectStorageService,
    private val guideService: GuideService,
    private val slugService: SlugService,
    private val documentService: DocumentService,
    private val dataService: DataService,
    private val collectionTemplateService: CollectionTemplateService,
    private val guideTemplateService: GuideTemplateService,
    private val documentTemplateService: DocumentTemplateService,
    private val dataTemplateService: DataTemplateService,
    private val json: Json,
    private val videoService: VideoService,
    private val sourceService: SourceService,
) : GraphQLController<MetadataMutation> {

    companion object {
        private val log = LoggerFactory.getLogger(MetadataMutationController::class.java)
    }

    @Field
    fun comments() = bosca.comments.graphql.CommentsMutation

    @Field
    fun ai() = MetadataAIMutation

    @Field
    suspend fun add(
        authentication: AuthenticationContext,
        metadata: MetadataInput,
        collectionItemAttributes: JsonElement?,
        setReady: Boolean?
    ): Metadata {
        val newMetadata = transaction {
            val parent = metadata.parentCollectionId?.let {
                collectionService.getById(it) ?: throw NoSuchElementException("Parent collection not found: $it")
            }
            if (parent != null) {
                collectionPermissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
            } else {
                groupEvaluator.verifyHasEditorGroup(authentication)
            }
            metadata.parentId?.let {
                val parentMetadata = service.getById(it) ?: throw NoSuchElementException("Parent metadata not found: $it")
                permissionEvaluator.verifyAllowed(authentication, parentMetadata, PermissionAction.EDIT)
            }
            service.add(parent, collectionItemAttributes, metadata)
        }
        if (setReady == true) {
            service.setReady(newMetadata, authentication.principal()?.asPrincipal() ?: error("No principal found"))
        }
        return newMetadata
    }

    @Field
    suspend fun setParentId(
        authentication: AuthenticationContext,
        id: UUID,
        parentId: UUID
    ): Metadata = transaction {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setParent(id, parentId)
        metadata.copy(parentId = parentId)
    }

    @Field
    suspend fun setMetadataName(
        authentication: AuthenticationContext,
        id: UUID,
        name: String
    ): Metadata {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        return service.setName(id, name) ?: throw IllegalStateException("Failed to set metadata title")
    }

    @Field
    suspend fun setMetadataSlug(
        authentication: AuthenticationContext,
        id: UUID,
        slug: String
    ): String = transaction {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        slugService.deleteMetadataSlug(id)
        if (slugService.get(slug) != null) error("Slug already exists")
        slugService.add(Slug(slug = slug, metadataId = id)).slug
    }

    @Field
    suspend fun addPermission(
        authentication: AuthenticationContext,
        permission: PermissionInput,
    ): EntityPermission = transaction {
        val metadata = service.getById(permission.entityId)
            ?: throw NoSuchElementException("Metadata not found: ${permission.entityId}")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.MANAGE)
        service.addPermission(permission)
    }

    @Field
    suspend fun deletePermission(
        authentication: AuthenticationContext,
        permission: PermissionInput,
    ): EntityPermission = transaction {
        val metadata = service.getById(permission.entityId)
            ?: throw NoSuchElementException("Metadata not found: ${permission.entityId}")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.MANAGE)
        service.deletePermission(permission)
    }

    @Field
    suspend fun setPublic(
        authentication: AuthenticationContext,
        id: UUID,
        public: Boolean
    ): Metadata {
        val metadata = service.getById(id)
            ?: throw NoSuchElementException("Metadata not found: $id")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setPublic(metadata, public)
        return metadata.copy(public = public)
    }

    /**
     * Set visibility, search eligibility, and optional recommendation eligibility for multiple metadata items
     * in a single operation.
     *
     * Each item is individually permission-checked. Items that do not exist, are locked,
     * or that the caller lacks permission to edit are silently skipped.
     * Returns the number of items successfully updated.
     */
    @Field
    suspend fun setPublicAll(
        authentication: AuthenticationContext,
        metadataIds: List<UUID>,
        public: Boolean,
        publicContent: Boolean,
        publicSupplementary: Boolean,
        searchable: Boolean,
        recommendable: Boolean? = null,
    ): Int {
        require(metadataIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        return transaction {
            val isSa = groupEvaluator.hasSaGroup(authentication)
            val items = service.getByIds(metadataIds)
            val unlocked = if (isSa) items else items.filter { !it.locked }
            val allowed = permissionEvaluator.filterAllowed(authentication, unlocked, PermissionAction.EDIT)
            for (metadata in allowed) {
                service.setPublic(metadata, public)
                service.setPublicContent(metadata, publicContent)
                service.setPublicSupplementary(metadata, publicSupplementary)
                service.setSearchable(metadata.id, searchable)
                recommendable?.let { service.setRecommendable(metadata.id, it) }
            }
            allowed.size
        }
    }

    /**
     * Set recommendation eligibility for multiple metadata items in a single operation.
     *
     * Each item is individually permission-checked. Items that do not exist, are locked,
     * or that the caller lacks permission to edit are silently skipped.
     */
    @Field
    suspend fun setRecommendableAll(
        authentication: AuthenticationContext,
        metadataIds: List<UUID>,
        recommendable: Boolean,
    ): Int {
        require(metadataIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        return transaction {
            val isSa = groupEvaluator.hasSaGroup(authentication)
            val items = service.getByIds(metadataIds)
            val unlocked = if (isSa) items else items.filter { !it.locked }
            val allowed = permissionEvaluator.filterAllowed(authentication, unlocked, PermissionAction.EDIT)
            for (metadata in allowed) {
                service.setRecommendable(metadata.id, recommendable)
            }
            allowed.size
        }
    }

    @Field
    suspend fun setPublicContent(
        authentication: AuthenticationContext,
        id: UUID,
        public: Boolean
    ): Metadata {
        val metadata = service.getById(id)
            ?: throw NoSuchElementException("Metadata not found: $id")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setPublicContent(metadata, public)
        return metadata.copy(publicContent = public)
    }

    @Field
    suspend fun setPublicSupplementary(
        authentication: AuthenticationContext,
        id: UUID,
        public: Boolean
    ): Metadata {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setPublicSupplementary(metadata, public)
        return metadata.copy(publicSupplementary = public)
    }

    @Field
    suspend fun setMetadataSearchable(
        authentication: AuthenticationContext,
        id: UUID,
        searchable: Boolean
    ): Boolean {
        val metadata = service.getById(id)
            ?: throw NoSuchElementException("Metadata not found: $id")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setSearchable(id, searchable)
        return true
    }

    @Field
    suspend fun setMetadataRecommendable(
        authentication: AuthenticationContext,
        id: UUID,
        recommendable: Boolean,
    ): Boolean {
        val metadata = service.getById(id)
            ?: throw NoSuchElementException("Metadata not found: $id")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setRecommendable(id, recommendable)
        return true
    }

    @Field
    suspend fun setMetadataCommentsEnabled(
        authentication: AuthenticationContext,
        id: UUID,
        enabled: Boolean
    ): Boolean {
        val metadata = service.getById(id)
            ?: throw NoSuchElementException("Metadata not found: $id")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setCommentsEnabled(id, enabled)
        // Replies are meaningless without comments, so disabling comments cascades to replies.
        if (!enabled && metadata.commentRepliesEnabled) {
            service.setCommentRepliesEnabled(id, false)
        }
        return true
    }

    @Field
    suspend fun setMetadataCommentRepliesEnabled(
        authentication: AuthenticationContext,
        id: UUID,
        enabled: Boolean
    ): Boolean {
        val metadata = service.getById(id)
            ?: throw NoSuchElementException("Metadata not found: $id")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setCommentRepliesEnabled(id, enabled)
        return true
    }

    @Field
    suspend fun setMetadataSyncVariantCollections(
        authentication: AuthenticationContext,
        id: UUID,
        syncVariants: Boolean
    ): Boolean {
        val metadata = service.getById(id)
            ?: throw NoSuchElementException("Metadata not found: $id")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setSyncVariantCollections(id, syncVariants)
        return true
    }

    @Field
    suspend fun setMetadataSyncVariantRelationships(
        authentication: AuthenticationContext,
        id: UUID,
        syncVariants: Boolean
    ): Boolean {
        val metadata = service.getById(id)
            ?: throw NoSuchElementException("Metadata not found: $id")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setSyncVariantRelationships(id, syncVariants)
        return true
    }

    @Field
    suspend fun addLanguageVariant(
        authentication: AuthenticationContext,
        id: UUID,
        version: Int,
        languageTag: String,
        setReady: Boolean?,
    ): Metadata {
        val newMetadata = transaction {
            val metadata = service.getById(id, version) ?: throw NoSuchElementException("Metadata not found: $id")
            if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
                throw SecurityException("locked")
            }
            permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
            val document = documentService.getDocument(metadata.id, metadata.version)
            val data = dataService.getData(metadata.id, metadata.version)
            val guide = guideService.getGuide(metadata.id, metadata.version)
            val slug = slugService.getMetadataSlug(id)
            val traitIds = service.getTraitIds(metadata.id)
            val categoryIds = service.getCategories(metadata.id).map { it.id }
            val newMetadata = service.add(
                null,
                null,
                MetadataInput(
                    name = metadata.name,
                    languageTag = languageTag,
                    contentType = metadata.contentType,
                    contentLength = metadata.contentLength,
                    metadataType = metadata.type,
                    parentId = metadata.id,
                    parentCollectionId = null,
                    slug = slug?.let { "$languageTag-$it" },
                    locked = metadata.locked,
                    attributes = metadata.attributes ?: JsonNull,
                    systemAttributes = metadata.systemAttributes,
                    document = document?.let {
                        DocumentInput(
                            it.templateMetadataId,
                            it.templateMetadataVersion,
                            it.title,
                            it.content
                        )
                    },
                    data = data?.let { da ->
                        DataInput(
                            da.templateMetadataId,
                            da.templateMetadataVersion,
                            da.type
                        )
                    },
                    guide = guide?.let {
                        val guideSteps = guideService.getGuideSteps(metadata.id, metadata.version)
                        GuideInput(
                            it.type,
                            it.rrule,
                            guideSteps.map {
                                val modules = guideService.getGuideStepModules(metadata.id, metadata.version, it.id)
                                GuideStepInput(
                                    stepMetadataId = it.stepMetadataId,
                                    stepMetadataVersion = it.stepMetadataVersion,
                                    modules = modules.map {
                                        GuideStepModuleInput(
                                            moduleMetadataId = it.moduleMetadataId,
                                            moduleMetadataVersion = it.moduleMetadataVersion,
                                        )
                                    },
                                )
                            },
                            it.templateMetadataId,
                            it.templateMetadataVersion,
                        )
                    },
                    labels = metadata.labels,
                    traitIds = traitIds,
                    categoryIds = categoryIds,
                    collectionTemplate = collectionTemplateService.getCollectionTemplate(metadata.id, metadata.version)?.let {
                        CollectionTemplateInput(
                            attributes = emptyList(),
                            defaultAttributes = it.defaultAttributes,
                            filters = it.filters.takeIf { it !is JsonNull }?.let { json.decodeFromJsonElement<CollectionTemplateFilters>(it) },
                            ordering = it.ordering?.takeIf { it !is JsonNull }?.let { json.decodeFromJsonElement<List<OrderingInput>>(it) } ?: emptyList(),
                            configuration = it.configuration,
                        )
                    },
                    guideTemplate = guideTemplateService.getTemplate(metadata.id, metadata.version)?.let {
                        GuideTemplateInput(
                            configuration = it.configuration,
                            defaultAttributes = it.defaultAttributes,
                            rrule = it.rrule,
                            steps = guideTemplateService.getTemplateSteps(metadata.id, metadata.version).map {
                                GuideTemplateStepInput(
                                    templateMetadataId = it.templateMetadataId ?: UUID.NIL,
                                    templateMetadataVersion = it.templateMetadataVersion ?: 1,
                                    modules = guideTemplateService.getTemplateStepModules(metadata.id, metadata.version, it.id).map {
                                        GuideTemplateStepModuleInput(
                                            templateMetadataId = it.templateMetadataId ?: UUID.NIL,
                                            templateMetadataVersion = it.templateMetadataVersion ?: 1,
                                        )
                                    }
                                )
                            },
                            type = it.type
                        )
                    },
                    documentTemplate = documentTemplateService.getTemplate(metadata.id, metadata.version)?.let {
                        DocumentTemplateInput(
                            configuration = it.configuration,
                            defaultAttributes = it.defaultAttributes,
                            schema = it.schema,
                            content = it.content,
                            attributes = documentTemplateService.getTemplateAttributes(metadata.id, metadata.version).map {
                                TemplateAttributeInput(
                                    key = it.key,
                                    name = it.name,
                                    description = it.description,
                                    supplementaryKey = it.supplementaryKey,
                                    configuration = it.configuration,
                                    type = it.type,
                                    ui = it.ui,
                                    list = it.list,
                                    location = it.location,
                                    workflows = documentTemplateService.getTemplateAttributeWorkflows(metadata.id, metadata.version, it.key).map {
                                        TemplateWorkflowInput(
                                            workflowId = it.workflowId,
                                            autoRun = it.autoRun,
                                        )
                                    }
                                )
                            },
                            containers = documentTemplateService.getTemplateContainers(metadata.id, metadata.version).map {
                                DocumentTemplateContainerInput(
                                    id = it.id,
                                    name = it.name,
                                    description = it.description,
                                    supplementaryKey = it.supplementaryKey,
                                    containerType = it.type,
                                    renderers = it.renderers?.let { r -> Json.decodeFromJsonElement<List<ContainerRendererInput>>(r) },
                                    filters = it.filters?.let { f -> Json.decodeFromJsonElement<List<String>>(f) },
                                    tools = it.tools?.let { t -> Json.decodeFromJsonElement<List<TemplateToolInput>>(t) }
                                )
                            }
                        )
                    },
                    dataTemplate = dataTemplateService.getTemplate(metadata.id, metadata.version)?.let {
                        DataTemplateInput(
                            type = it.type,
                            defaultAttributes = it.defaultAttributes,
                            attributes = dataTemplateService.getTemplateAttributes(metadata.id, metadata.version).map {
                                TemplateAttributeInput(
                                    key = it.key,
                                    name = it.name,
                                    description = it.description,
                                    supplementaryKey = it.supplementaryKey,
                                    configuration = it.configuration,
                                    type = it.type,
                                    ui = it.ui,
                                    list = it.list,
                                    location = it.location,
                                    workflows = dataTemplateService.getTemplateAttributeWorkflows(metadata.id, metadata.version, it.key).map {
                                        TemplateWorkflowInput(
                                            workflowId = it.workflowId,
                                            autoRun = it.autoRun,
                                        )
                                    }
                                )
                            }
                        )
                    },
                    source = metadata.sourceId?.let {
                        MetadataSourceInput(
                            id = it,
                            identifier = metadata.sourceIdentifier,
                            sourceUrl = metadata.sourceUrl
                        )
                    },
                    profiles = emptyList()
                )
            )
            val parents = collectionService.getMetadataParents(metadata.id)
            parents.forEach { parent ->
                collectionService.addMetadataItem(parent.id, newMetadata.id, parent.attributes ?: emptyMap<String, Any>().toJsonElement())
            }
            val relationships = service.getRelationships(metadata.id)
            relationships.forEach { relationship ->
                service.addRelationship(
                    MetadataRelationshipInput(
                        id1 = newMetadata.id,
                        id2 = relationship.metadataId2,
                        relationship = relationship.relationship,
                        attributes = relationship.attributes ?: emptyMap<String, Any>().toJsonElement()
                    )
                )
            }
            val permissions = service.getPermissions(metadata)
            permissions.forEach { permission ->
                service.addPermission(
                    PermissionInput(
                        action = permission.action,
                        entityId = newMetadata.id,
                        groupId = permission.groupId
                    )
                )
            }
            newMetadata
        }
        if (setReady == true) {
            service.setReady(newMetadata, authentication.principal()?.asPrincipal() ?: error("No principal found"))
        }
        return newMetadata
    }

    @Field
    suspend fun addDocument(
        authentication: AuthenticationContext,
        parentCollectionId: UUID,
        templateId: UUID,
        templateVersion: Int,
        setReady: Boolean?
    ): Metadata {
        val metadata = transaction {
            val collection = collectionService.getById(parentCollectionId) ?: throw NoSuchElementException("Collection not found: $parentCollectionId")
            collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
            if (collection.itemsLocked && !groupEvaluator.hasEditorGroup(authentication)) {
                throw IllegalStateException("locked")
            }
            val template = service.getById(templateId, templateVersion)
                ?: throw NoSuchElementException("Template not found: $templateId")
            val metadata = service.addDocument(parentCollectionId, template)
            collectionService.addMetadataItem(
                collection.id,
                metadata.id,
                emptyMap<String, Any>().toJsonElement()
            )
            metadata
        }
        if (setReady == true) {
            service.setReady(metadata, authentication.principal()?.asPrincipal() ?: error("No principal found"))
        }
        return metadata
    }

    @Field
    suspend fun setMetadataDocument(
        authentication: AuthenticationContext,
        id: UUID,
        version: Int,
        document: DocumentInput,
        collaborationSync: CollaborationSyncMode? = null,
    ): Boolean {
        val metadata = service.getById(id, version) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setDocument(metadata, document, collaborationSync ?: CollaborationSyncMode.NONE)
        return true
    }

    @Field
    suspend fun setMetadataMarkdown(
        authentication: AuthenticationContext,
        id: UUID,
        version: Int,
        title: String,
        markdown: String,
        collaborationSync: CollaborationSyncMode? = null,
    ): Boolean {
        val metadata = service.getById(id, version) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        val content = bosca.documents.MarkdownConverter.fromMarkdown(markdown)
        service.setDocument(metadata, DocumentInput(title = title, content = content), collaborationSync ?: CollaborationSyncMode.NONE)
        return true
    }

    @Field
    suspend fun setMetadataAttributes(
        authentication: AuthenticationContext,
        id: UUID,
        attributes: JsonElement
    ): Boolean {
        val metadata = service.getById(id)
            ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setAttributes(metadata, attributes)
        return true
    }

    @Field
    suspend fun mergeMetadataAttributes(
        authentication: AuthenticationContext,
        id: UUID,
        attributes: JsonElement
    ): Boolean {
        val metadata = service.getById(id)
            ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.mergeAttributes(metadata, attributes)
        return true
    }

    @Field
    suspend fun addGuide(
        authentication: AuthenticationContext,
        parentCollectionId: UUID?,
        templateId: UUID,
        templateVersion: Int,
        setReady: Boolean?
    ): Metadata {
        val metadata = transaction {
            if (parentCollectionId != null) {
                val collection = collectionService.getById(parentCollectionId)
                    ?: throw NoSuchElementException("Collection not found: $parentCollectionId")
                collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
                if (collection.itemsLocked && !groupEvaluator.hasEditorGroup(authentication)) {
                    throw IllegalStateException("locked")
                }
            }
            val template = service.getById(templateId, templateVersion)
                ?: throw NoSuchElementException("Template not found: $templateId")
            val metadata = service.addGuide(parentCollectionId, template)
            if (parentCollectionId != null) {
                collectionService.addMetadataItem(
                    parentCollectionId,
                    metadata.id,
                    emptyMap<String, Any>().toJsonElement()
                )
            }
            metadata
        }
        if (setReady == true) {
            service.setReady(metadata, authentication.principal()?.asPrincipal() ?: error("No principal found"))
        }
        return metadata
    }

    @Field
    suspend fun addData(
        authentication: AuthenticationContext,
        parentCollectionId: UUID?,
        templateId: UUID,
        templateVersion: Int,
        setReady: Boolean?
    ): Metadata {
        val metadata = transaction {
            if (parentCollectionId != null) {
                val collection = collectionService.getById(parentCollectionId) ?: throw NoSuchElementException("Collection not found: $parentCollectionId")
                collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.EDIT)
                if (collection.itemsLocked && !groupEvaluator.hasEditorGroup(authentication)) {
                    throw IllegalStateException("locked")
                }
            }
            val template = service.getById(templateId, templateVersion)
                ?: throw NoSuchElementException("Template not found: $templateId")
            val metadata = service.addData(parentCollectionId, template)
            if (parentCollectionId != null) {
                collectionService.addMetadataItem(
                    parentCollectionId,
                    metadata.id,
                    emptyMap<String, Any>().toJsonElement()
                )
            }
            metadata
        }
        if (setReady == true) {
            service.setReady(metadata, authentication.principal()?.asPrincipal() ?: error("No principal found"))
        }
        return metadata
    }

    @Field
    suspend fun setGuideStartDate(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int,
        date: OffsetDateTime
    ): Metadata {
        val metadata = service.getById(metadataId, metadataVersion)
            ?: throw NoSuchElementException("Metadata not found: $metadataId")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        val guide = guideService.getGuide(metadataId, metadataVersion) ?: throw NoSuchElementException("Guide not found: $metadataId")
        val rrule = guide.rrule ?: throw IllegalStateException("Guide has no recurrence rule")
        val dateTimePattern = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        if (!rrule.contains("DTSTART")) {
            guideService.setGuideRrule(
                metadata.id,
                metadata.version,
                "DTSTART:${dateTimePattern.format(Date(date.toInstant().toEpochMilli()))}\n$rrule"
            )
        } else {
            val parts = rrule.split("\n").filter { !it.startsWith("DTSTART") }
            guideService.setGuideRrule(
                metadata.id,
                metadata.version,
                "DTSTART:${dateTimePattern.format(Date(date.toInstant().toEpochMilli()))}\n${parts.joinToString("\n")}"
            )
        }
        return metadata
    }

    @Field
    suspend fun addGuideStep(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int,
        sort: Int,
        templateStepId: Long
    ): GuideStepContext = transaction {
        val metadata =
            service.getById(metadataId, metadataVersion) ?: throw NoSuchElementException("Guide not found: $metadataId")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        val guide = guideService.getGuide(metadataId, metadataVersion) ?: throw NoSuchElementException("Guide not found: $metadataId")
        val step = service.addGuideStep(
            guide,
            templateStepId,
            sort
        )
        val recurrences = guide.getRecurrenceDates(step.sort + 1)
        val date = recurrences.getOrNull(step.sort)
        GuideStepContext(guide, step, date)
    }
    @Field
    suspend fun addGuideStepModule(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int,
        sort: Int,
        stepId: Long,
        templateModuleId: Long
    ): GuideStepModule {
        val metadata = service.getById(metadataId, metadataVersion) ?: throw NoSuchElementException("Guide not found: $metadataId")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        val guide = guideService.getGuide(metadataId, metadataVersion) ?: throw NoSuchElementException("Guide not found: $metadataId")
        return service.addGuideStepModule(guide, stepId, templateModuleId, sort)
    }

    @Field
    suspend fun deleteGuide(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int
    ): Boolean {
        val metadata = service.getById(metadataId, metadataVersion) ?: throw NoSuchElementException("Guide not found: $metadataId")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.deleteGuide(metadata)
        return true
    }

    @Field
    suspend fun deleteGuideStep(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int,
        stepId: Long
    ): Boolean {
        val metadata = service.getById(metadataId, metadataVersion) ?: throw NoSuchElementException("Guide not found: $metadataId")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.deleteGuideStep(metadata, stepId)
        return true
    }

    @Field
    suspend fun deleteGuideStepModule(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int,
        stepId: Long,
        moduleId: Long
    ): Boolean {
        val metadata = service.getById(metadataId, metadataVersion) ?: throw NoSuchElementException("Guide not found: $metadataId")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.deleteGuideStepModule(metadata, stepId, moduleId)
        return true
    }

    @Field
    suspend fun edit(
        authentication: AuthenticationContext,
        id: UUID,
        metadata: MetadataInput
    ): Metadata {
        val current = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, current, PermissionAction.EDIT)
        return service.edit(id, metadata)
    }

    @Field
    suspend fun setTemplate(
        authentication: AuthenticationContext,
        id: UUID,
        version: Int,
        templateId: UUID,
        templateVersion: Int
    ): Metadata {
        val current = service.getById(id, version) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, current, PermissionAction.EDIT)
        when (current.contentType) {
            "bosca/v-document",
            "bosca/v-guide-step" -> service.setDocumentTemplate(current, templateId, templateVersion)
            "bosca/v-data" -> service.setDataTemplate(current, templateId, templateVersion)
            else -> throw IllegalArgumentException("Invalid Metadata Content Type: ${current.contentType}")
        }
        return current
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, metadataId: UUID): Boolean {
        val current = service.getById(metadataId) ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, current, PermissionAction.DELETE)
        service.markDeleted(metadataId)
        return true
    }

    /**
     * Soft-delete multiple metadata items in a single operation.
     *
     * Each item is individually permission-checked before deletion. Items that
     * do not exist or that the caller lacks permission to delete are silently
     * skipped. Returns the number of items successfully deleted.
     */
    @Field
    suspend fun deleteAll(authentication: AuthenticationContext, metadataIds: List<UUID>): Int {
        require(metadataIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        return transaction {
            val items = service.getByIds(metadataIds)
            val allowed = permissionEvaluator.filterAllowed(authentication, items, PermissionAction.DELETE)
            for (metadata in allowed) {
                service.markDeleted(metadata.id)
            }
            allowed.size
        }
    }

    @Field
    suspend fun setMetadataSystemAttributes(
        authentication: AuthenticationContext,
        id: UUID,
        attributes: JsonElement
    ): Boolean {
        val current = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, current, PermissionAction.EDIT)
        service.setSystemAttributes(current, attributes)
        return true
    }

    @Field
    suspend fun importUrl(
        authentication: AuthenticationContext,
        metadata: MetadataInput,
        collectionItemAttributes: JsonElement?,
        url: String,
        headers: JsonElement?,
        ready: Boolean?,
    ): Metadata {
        groupEvaluator.verifyHasEditorGroup(authentication)
        val newMetadata = transaction {
            val parent = metadata.parentCollectionId?.let {
                collectionService.getById(it) ?: throw NoSuchElementException("Parent collection not found: $it")
            }
            if (parent != null) {
                collectionPermissionEvaluator.verifyAllowed(authentication, parent, PermissionAction.EDIT)
            }
            metadata.parentId?.let {
                val parentMetadata = service.getById(it) ?: throw NoSuchElementException("Parent metadata not found: $it")
                permissionEvaluator.verifyAllowed(authentication, parentMetadata, PermissionAction.EDIT)
            }
            val source = sourceService.getOrCreateForUrl(url)
            val metadataWithSource = metadata.copy(
                source = (metadata.source ?: MetadataSourceInput()).copy(
                    id = metadata.source?.id ?: source.id,
                    sourceUrl = url,
                )
            )
            val created = service.add(parent, collectionItemAttributes, metadataWithSource)
            service.setSourceStatus(created.id, SourceStatus.PENDING)
            created
        }
        val headerMap = headers?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement<Map<String, String>>(it) }
        ImportUrlJob(
            id = newMetadata.id,
            url = url,
            contentType = metadata.contentType,
            headers = headerMap,
            ready = ready ?: false,
            principalId = authentication.principal()?.id,
        ).enqueue()
        return newMetadata
    }

    @Field
    suspend fun setMetadataContents(
        authentication: AuthenticationContext,
        contentType: String?,
        file: UploadedFile,
        id: UUID
    ): Boolean {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        val fileItem = PartData.UploadedFileItem(file)
        try {
            val contentLength = storage.upload(metadata, null, fileItem)
            service.setUploaded(id, contentType, contentLength)
        } finally {
            fileItem.dispose()
        }
        return true
    }

    @Field
    suspend fun setMetadataJsonContents(
        authentication: AuthenticationContext,
        id: UUID,
        contentType: String?,
        content: JsonElement,
    ): Boolean {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        val json = content
        val contentLength = storage.upload(
            metadata,
            null,
            json.toString()
        )
        service.setUploaded(id, contentType, contentLength)
        return true
    }

    @Field
    suspend fun setMetadataTextContents(
        authentication: AuthenticationContext,
        id: UUID,
        contentType: String?,
        content: String,
    ): Boolean = transaction {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        val contentLength = storage.upload(
            metadata,
            null,
            content
        )
        service.setUploaded(id, contentType, contentLength)
        true
    }

    @Field
    suspend fun clearDocumentCollaboration(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int
    ): Boolean {
        val metadata = service.getById(metadataId, metadataVersion) ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        documentService.removeCollaboration(metadataId, metadataVersion)
        return true
    }

    @Field
    suspend fun clearDataCollaboration(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int
    ): Boolean {
        val metadata = service.getById(metadataId, metadataVersion) ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        dataService.removeCollaboration(metadataId, metadataVersion)
        return true
    }

    @Field
    suspend fun deleteContent(
        authentication: AuthenticationContext,
        metadataId: UUID
    ): Boolean = transaction {
        val metadata = service.getById(metadataId) ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        val path = storage.getPath(metadata)
        storage.delete(path)
        service.clearUploaded(metadataId)
        true
    }

    @Field
    suspend fun setMetadataUploaded(
        authentication: AuthenticationContext,
        id: UUID,
        contentType: String?,
        len: Int,
        ready: Boolean?,
    ): Boolean {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setUploaded(id, contentType ?: metadata.contentType, len.toLong())
        if (ready == true) {
            service.setReady(metadata, authentication.principal()?.asPrincipal() ?: error("missing principal"))
        }
        return true
    }

    @Field
    suspend fun setMetadataParentCollections(authentication: AuthenticationContext, id: UUID, collections: List<MetadataParentCollection>): Boolean = transaction {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        val parents = service.getParents(id)
        val newIds = collections.mapTo(mutableSetOf()) { it.id }
        val oldIds = parents.mapTo(mutableSetOf()) { it.id }

        for (parent in parents.filter { it.id !in newIds }) {
            val col = collectionService.getById(parent.id) ?: throw NoSuchElementException("Collection not found: ${parent.id}")
            collectionPermissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT)
            collectionService.removeMetadataItem(parent.id, id)
        }
        for (collection in collections) {
            val col = collectionService.getById(collection.id) ?: throw NoSuchElementException("Collection not found: ${collection.id}")
            collectionPermissionEvaluator.verifyAllowed(authentication, col, PermissionAction.EDIT)
            if (collection.id in oldIds) {
                collection.attributes?.let {
                    collectionService.mergeMetadataItemAttributes(collection.id, id, it)
                }
            } else {
                collectionService.addMetadataItem(collection.id, id, collection.attributes)
            }
        }
        true
    }

    @Field
    suspend fun setMetadataRelationships(authentication: AuthenticationContext, id: UUID, relationships: List<MetadataRelationshipInput>): Boolean = transaction {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        val current = service.getRelationships(id)
        val newMap = relationships.associateBy { Triple(it.id1, it.id2, it.relationship) }
        val oldMap = current.associateBy { Triple(it.metadataId1, it.metadataId2, it.relationship) }

        for (relationship in current.filter { Triple(it.metadataId1, it.metadataId2, it.relationship) !in newMap.keys }) {
            service.removeRelationship(relationship.metadataId1, relationship.metadataId2, relationship.relationship)
        }
        for (relationship in relationships) {
            if (relationship.id1 != id) throw IllegalArgumentException("Invalid relationship: $relationship")
            val key = Triple(relationship.id1, relationship.id2, relationship.relationship)
            if (key in oldMap.keys) {
                relationship.attributes?.let {
                    service.mergeAttributes(relationship.id1, relationship.id2, relationship.relationship, it)
                }
            } else {
                service.addRelationship(relationship)
            }
        }
        true
    }

    @Field
    suspend fun setLocked(
        authentication: AuthenticationContext,
        id: UUID,
        version: Int,
        locked: Boolean,
    ): Metadata {
        val metadata = service.getById(id, version) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setLocked(id, version, locked)
        return metadata.copy(locked = locked)
    }

    @Field
    suspend fun addCategory(
        authentication: AuthenticationContext,
        metadataId: UUID,
        categoryId: UUID
    ): Boolean {
        val metadata = service.getById(metadataId) ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.addCategory(metadataId, categoryId)
        return true
    }

    @Field
    suspend fun deleteCategory(
        authentication: AuthenticationContext,
        categoryId: UUID,
        metadataId: UUID
    ): Boolean {
        val metadata = service.getById(metadataId) ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.deleteCategory(metadataId, categoryId)
        return true
    }

    @Field
    suspend fun setCategories(
        authentication: AuthenticationContext,
        categoryIds: List<UUID>,
        metadataId: UUID
    ): Boolean {
        val metadata = service.getById(metadataId) ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setCategories(metadataId, categoryIds)
        return true
    }

    @Field
    suspend fun addTrait(
        authentication: AuthenticationContext,
        metadataId: UUID,
        traitId: String
    ) {
        val metadata = service.getById(metadataId) ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.addTrait(metadataId, traitId)
        // TODO: fire off workflows
    }

    @Field
    suspend fun deleteTrait(
        authentication: AuthenticationContext,
        metadataId: UUID,
        traitId: String
    ) {
        val metadata = service.getById(metadataId) ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.deleteTrait(metadataId, traitId)
        // TODO: fire off workflows
    }

    @Field
    suspend fun addRelationship(
        authentication: AuthenticationContext,
        relationship: MetadataRelationshipInput
    ): MetadataRelationship {
        val metadata1 =
            service.getById(relationship.id1) ?: throw NoSuchElementException("Metadata not found: ${relationship.id1}")
        val metadata2 =
            service.getById(relationship.id2) ?: throw NoSuchElementException("Metadata not found: ${relationship.id2}")
        permissionEvaluator.verifyAllowed(authentication, metadata1, PermissionAction.EDIT)
        permissionEvaluator.verifyAllowed(authentication, metadata2, PermissionAction.EDIT)
        return service.addRelationship(relationship)
    }

    @Field
    suspend fun editRelationship(
        authentication: AuthenticationContext,
        relationship: MetadataRelationshipInput
    ): Boolean {
        val metadata1 = service.getById(relationship.id1) ?: throw NoSuchElementException("Metadata not found: ${relationship.id1}")
        val metadata2 = service.getById(relationship.id2) ?: throw NoSuchElementException("Metadata not found: ${relationship.id2}")
        permissionEvaluator.verifyAllowed(authentication, metadata1, PermissionAction.EDIT)
        permissionEvaluator.verifyAllowed(authentication, metadata2, PermissionAction.EDIT)
        service.removeRelationship(relationship.id1, relationship.id2, relationship.relationship)
        service.addRelationship(relationship)
        return true
    }

    @Field
    suspend fun deleteRelationship(
        authentication: AuthenticationContext,
        id1: UUID,
        id2: UUID,
        relationship: String
    ): Boolean {
        val metadata1 = service.getById(id1) ?: throw NoSuchElementException("Metadata not found: $id1")
        val metadata2 = service.getById(id2) ?: throw NoSuchElementException("Metadata not found: $id2")
        permissionEvaluator.verifyAllowed(authentication, metadata1, PermissionAction.EDIT)
        permissionEvaluator.verifyAllowed(authentication, metadata2, PermissionAction.EDIT)
        service.removeRelationship(id1, id2, relationship)
        return true
    }

    @Field
    suspend fun mergeMetadataRelationshipAttributes(
        authentication: AuthenticationContext,
        metadata1Id: UUID,
        metadata2Id: UUID,
        relationship: String,
        attributes: JsonElement,
    ): Boolean {
        val metadata1 = service.getById(metadata1Id) ?: throw NoSuchElementException("Metadata not found: $metadata1Id")
        val metadata2 = service.getById(metadata2Id) ?: throw NoSuchElementException("Metadata not found: $metadata2Id")
        permissionEvaluator.verifyAllowed(authentication, metadata1, PermissionAction.EDIT)
        permissionEvaluator.verifyAllowed(authentication, metadata2, PermissionAction.EDIT)
        service.mergeAttributes(metadata1Id, metadata2Id, relationship, attributes)
        return true
    }

    @Field
    suspend fun setMetadataNotReady(
        authentication: AuthenticationContext,
        id: UUID
    ): Boolean {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setNotReady(metadata)
        return true
    }

    @Field
    suspend fun setMetadataReady(
        authentication: AuthenticationContext,
        id: UUID
    ): Boolean {
        val metadata = service.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setReady(metadata, authentication.principal()?.asPrincipal() ?: error("missing principal"))
        return true
    }

    /**
     * Mark multiple metadata items as ready for publishing in a single operation.
     *
     * Each item is individually permission-checked and processed independently so that
     * a failure on one item does not prevent the others from succeeding. Items that do
     * not exist, are locked, or that the caller lacks permission to edit are silently
     * skipped. Returns the number of items successfully marked as ready.
     */
    @Field
    suspend fun setMetadataReadyAll(
        authentication: AuthenticationContext,
        metadataIds: List<UUID>
    ): Int {
        require(metadataIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        val isSa = groupEvaluator.hasSaGroup(authentication)
        val principal = authentication.principal()?.asPrincipal() ?: error("missing principal")
        val items = service.getByIds(metadataIds)
        val unlocked = if (isSa) items else items.filter { !it.locked }
        val allowed = permissionEvaluator.filterAllowed(authentication, unlocked, PermissionAction.EDIT)
        var succeeded = 0
        for (metadata in allowed) {
            try {
                service.setReady(metadata, principal)
                succeeded++
            } catch (e: Exception) {
                log.error("Failed to set ready for metadata {}: {}", metadata.id, e.message)
            }
        }
        return succeeded
    }

    @Field
    suspend fun addSupplementary(
        authentication: AuthenticationContext,
        supplementary: MetadataSupplementaryInput
    ): MetadataSupplementaryContext {
        val metadata = service.getById(supplementary.metadataId) ?: throw NoSuchElementException("Metadata not found: ${supplementary.metadataId}")
        if (!permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EDIT)) {
            groupEvaluator.verifyHasSaGroup(authentication)
        }
        return MetadataSupplementaryContext(
            metadata = metadata,
            supplementary = service.addSupplementary(supplementary)
        )
    }

    @Field
    suspend fun setSupplementaryTextContents(
        authentication: AuthenticationContext,
        supplementaryId: UUID,
        content: String,
        contentType: String,
    ): Boolean {
        val supplementaryItem = service.getSupplementaryById(supplementaryId) ?: throw NoSuchElementException("Supplementary item not found: $supplementaryId")
        val metadata = service.getById(supplementaryItem.metadataId) ?: throw NoSuchElementException("Metadata not found: ${supplementaryItem.metadataId}")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.updateSupplementaryContent(metadata, supplementaryId, content, contentType)
        return true
    }

    @Field
    suspend fun setSupplementaryContents(
        authentication: AuthenticationContext,
        supplementaryId: UUID,
        file: UploadedFile,
        contentType: String,
    ): Boolean {
        val supplementaryItem = service.getSupplementaryById(supplementaryId) ?: throw NoSuchElementException("Supplementary item not found: $supplementaryId")
        val metadata = service.getById(supplementaryItem.metadataId) ?: throw NoSuchElementException("Metadata not found: ${supplementaryItem.metadataId}")
        if (!permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EDIT)) {
            groupEvaluator.verifyHasSaGroup(authentication)
        }
        val fileItem = PartData.UploadedFileItem(file)
        try {
            service.updateSupplementaryContent(metadata, supplementaryId, fileItem, contentType)
        } finally {
            fileItem.dispose()
        }
        return true
    }

    @Field
    suspend fun setSupplementaryUploaded(
        authentication: AuthenticationContext,
        supplementaryId: UUID,
        len: Long,
        contentType: String,
    ): Boolean {
        val supplementaryItem = service.getSupplementaryById(supplementaryId) ?: throw NoSuchElementException("Supplementary item not found: $supplementaryId")
        val metadata = service.getById(supplementaryItem.metadataId) ?: throw NoSuchElementException("Metadata not found: ${supplementaryItem.metadataId}")
        if (!permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EDIT)) {
            groupEvaluator.verifyHasSaGroup(authentication)
        }
        service.setSupplementaryUploaded(metadata, supplementaryId, len, contentType)
        return true
    }

    @Field
    suspend fun deleteSupplementary(
        authentication: AuthenticationContext,
        id: UUID,
    ): Boolean {
        val supplementary = service.getSupplementaryById(id) ?: return false
        val metadata = service.getById(supplementary.metadataId) ?: return false
        if (!permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EDIT)) {
            groupEvaluator.verifyHasSaGroup(authentication)
        }
        service.deleteSupplementary(metadata, id)
        return true
    }
    @Field
    suspend fun detachSupplementary(
        authentication: AuthenticationContext,
        id: UUID,
    ): Boolean {
        val supplementary = service.getSupplementaryById(id) ?: return false
        val metadata = service.getById(supplementary.metadataId) ?: return false
        if (!permissionEvaluator.isAllowed(authentication, metadata, PermissionAction.EDIT)) {
            groupEvaluator.verifyHasSaGroup(authentication)
        }
        service.detachSupplementary(metadata, id)
        return true
    }

    @Field
    suspend fun setWorkflowState(
        authentication: AuthenticationContext,
        state: MetadataWorkflowState
    ): Boolean {
        val principal = authentication.principal() ?: return false
        val metadata = service.getById(state.metadataId) ?: return false
        groupEvaluator.verifyHasSaGroup(authentication)
        if (state.immediate) {
            service.setState(
                principal = principal.asPrincipal(),
                item = metadata,
                toStateId = state.stateId,
                status = state.status,
            )
        } else {
            service.setPendingState(
                principal = principal.asPrincipal(),
                item = metadata,
                toStateId = state.stateId,
                status = state.status,
            )
        }
        return true
    }

    @Field
    suspend fun setWorkflowStateComplete(
        authentication: AuthenticationContext,
        state: MetadataWorkflowCompleteState
    ): Boolean {
        val principal = authentication.principal() ?: return false
        val metadata = service.getById(state.metadataId) ?: return false
        groupEvaluator.verifyHasSaGroup(authentication)
        service.setPendingStateComplete(
            item = metadata,
            status = state.status,
            principal = principal.asPrincipal(),
        )
        return true
    }

    @Field
    suspend fun setMetadataBible(
        authentication: AuthenticationContext,
        id: UUID,
        version: Int,
        bible: BibleInput
    ): Boolean {
        val metadata = service.getById(id, version) ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setBible(metadata, bible)
        return true
    }

    @Field
    suspend fun setMetadataBibleVariantEnabled(
        authentication: AuthenticationContext,
        id: UUID,
        version: Int,
        variant: String,
        enabled: Boolean,
    ): Boolean {
        val metadata = service.getById(id, version)
            ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setBibleVariantEnabled(metadata, variant, enabled)
        return true
    }

    @Field
    suspend fun setMetadataBibleDefaultVariant(
        authentication: AuthenticationContext,
        id: UUID,
        version: Int,
        variant: String,
    ): Boolean {
        val metadata = service.getById(id, version)
            ?: throw NoSuchElementException("Metadata not found: $id")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        service.setDefaultBibleVariant(metadata, variant)
        return true
    }

    @Field
    suspend fun permanentlyDelete(
        authentication: AuthenticationContext,
        metadataId: UUID
    ): Boolean {
        val metadata = service.getById(metadataId) ?: return false
        groupEvaluator.verifyHasSaGroup(authentication)
        service.delete(metadata)
        return true
    }

    @Field
    suspend fun guide(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int
    ): GuideMutation {
        val metadata = service.getById(metadataId, metadataVersion)
            ?: throw NoSuchElementException("Metadata not found: $metadataId")
        if (metadata.locked && !groupEvaluator.hasSaGroup(authentication)) {
            throw SecurityException("locked")
        }
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        return GuideMutation(metadata)
    }

    @Field
    suspend fun guideTemplate(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int
    ): GuideTemplateMutation {
        val metadata = service.getById(metadataId, metadataVersion)
            ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        return GuideTemplateMutation(metadata)
    }

    @Field
    suspend fun documentTemplate(
        authentication: AuthenticationContext,
        @Suppress("UNUSED_PARAMETER")
        mutation: MetadataMutation,
        metadataId: UUID,
        metadataVersion: Int
    ): DocumentTemplateMutation {
        val metadata = service.getById(metadataId, metadataVersion)
            ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        return DocumentTemplateMutation(metadata)
    }

    @Field
    suspend fun dataTemplate(
        authentication: AuthenticationContext,
        @Suppress("UNUSED_PARAMETER")
        mutation: MetadataMutation,
        metadataId: UUID,
        metadataVersion: Int
    ): DataTemplateMutation {
        val metadata = service.getById(metadataId, metadataVersion)
            ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        return DataTemplateMutation(metadata)
    }

    @Field
    suspend fun collectionTemplate(
        authentication: AuthenticationContext,
        @Suppress("UNUSED_PARAMETER")
        mutation: MetadataMutation,
        metadataId: UUID,
        metadataVersion: Int
    ): CollectionTemplateMutation {
        val metadata = service.getById(metadataId, metadataVersion)
            ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        return CollectionTemplateMutation(metadata)
    }

    @Field
    suspend fun reprocessBible(authentication: AuthenticationContext, metadataId: UUID, metadataVersion: Int): Boolean {
        val metadata = service.getById(metadataId, metadataVersion)
            ?: throw NoSuchElementException("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        BibleProcessJob(metadata.id, metadata.version).enqueue()
        return true
    }

    /**
     * Submits a metadata item's video content to the configured external
     * transcoding provider for adaptive-bitrate processing, with optional
     * per-item quality overrides.
     */
    @Field
    suspend fun processMedia(
        authentication: AuthenticationContext,
        id: UUID,
        options: MediaProcessingOptions?,
    ): Boolean {
        val metadata = service.getById(id) ?: error("Metadata not found")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.MANAGE)
        videoService.process(metadata, options)
        MetadataUpdated(metadata).dispatch()
        return true
    }

    /**
     * Removes the processed media asset from the external transcoding
     * provider and deletes the local media record for a metadata item.
     */
    @Field
    suspend fun deleteMedia(
        authentication: AuthenticationContext,
        id: UUID,
    ): Boolean {
        val metadata = service.getById(id) ?: error("Metadata not found")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.MANAGE)
        videoService.delete(metadata)
        return true
    }

    /**
     * Updates the thumbnail time offset for a processed media asset,
     * rebuilding the thumbnail URL using the provider's image CDN.
     */
    @Field
    suspend fun setMediaThumbnailOffset(
        authentication: AuthenticationContext,
        id: UUID,
        offsetSeconds: Double,
    ): Boolean {
        val metadata = service.getById(id) ?: error("Metadata not found")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.MANAGE)
        videoService.setThumbnailTimeOffset(metadata, offsetSeconds)
        MetadataUpdated(metadata).dispatch()
        return true
    }

    /**
     * Updates video quality and/or resolution settings on an existing
     * processed media asset, triggering re-transcoding on the provider.
     */
    @Field
    suspend fun updateMediaSettings(
        authentication: AuthenticationContext,
        id: UUID,
        options: MediaProcessingOptions,
    ): Boolean {
        val metadata = service.getById(id) ?: error("Metadata not found")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.MANAGE)
        videoService.updateMediaSettings(metadata, options)
        MetadataUpdated(metadata).dispatch()
        return true
    }

    /**
     * Submits multiple metadata items' video content to the configured
     * external transcoding provider for adaptive-bitrate processing.
     *
     * Each item is individually permission-checked. Items that do not exist
     * or that the caller lacks permission to edit are silently skipped.
     * Returns the number of items successfully submitted for processing.
     */
    @Field
    suspend fun processMediaAll(
        authentication: AuthenticationContext,
        metadataIds: List<UUID>,
    ): Int {
        require(metadataIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        val items = service.getByIds(metadataIds)
        val allowed = permissionEvaluator.filterAllowed(authentication, items, PermissionAction.MANAGE)
        var processed = 0
        for (metadata in allowed) {
            try {
                videoService.process(metadata)
                MetadataUpdated(metadata).dispatch()
                processed++
            } catch (e: Exception) {
                log.error("failed to process media for metadata ${metadata.id}", e)
            }
        }
        return processed
    }

    /**
     * Removes the processed media assets from the external transcoding
     * provider and deletes the local media records for multiple metadata items.
     *
     * Each item is individually permission-checked. Items that do not exist
     * or that the caller lacks permission to edit are silently skipped.
     * Returns the number of items successfully deleted.
     */
    @Field
    suspend fun deleteMediaAll(
        authentication: AuthenticationContext,
        metadataIds: List<UUID>,
    ): Int {
        require(metadataIds.size <= MAX_BULK_OPERATION_SIZE) { "Cannot process more than $MAX_BULK_OPERATION_SIZE items at once" }
        val items = service.getByIds(metadataIds)
        val allowed = permissionEvaluator.filterAllowed(authentication, items, PermissionAction.MANAGE)
        var deleted = 0
        for (metadata in allowed) {
            try {
                videoService.delete(metadata)
                MetadataUpdated(metadata).dispatch()
                deleted++
            } catch (e: Exception) {
                log.error("failed to delete media for metadata ${metadata.id}", e)
            }
        }
        return deleted
    }
}
