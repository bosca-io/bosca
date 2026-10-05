package bosca.content.metadata.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.category.model.Category
import bosca.category.service.CategoryService
import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.content.embedding.model.EmbeddingChunk
import bosca.content.find.FindQueryInput
import bosca.content.metadata.events.MetadataCreated
import bosca.content.metadata.events.MetadataDeleted
import bosca.content.metadata.events.MetadataLockedEvent
import bosca.content.metadata.events.MetadataRelationshipAdded
import bosca.content.metadata.events.MetadataRelationshipMerged
import bosca.content.metadata.events.MetadataRelationshipRemoved
import bosca.content.metadata.events.MetadataSetNotReady
import bosca.content.metadata.events.MetadataSetReady
import bosca.content.metadata.events.MetadataSetTraits
import bosca.content.metadata.events.MetadataStateChangeComplete
import bosca.content.metadata.events.MetadataStateChanged
import bosca.content.metadata.events.MetadataSupplementedUpdatedEvent
import bosca.content.metadata.events.MetadataUnlockedEvent
import bosca.content.metadata.events.MetadataUpdated
import bosca.content.metadata.events.MetadataUploadCleared
import bosca.content.metadata.events.MetadataUploadedEvent
import bosca.content.metadata.events.dispatch
import bosca.content.collaboration.CollaborationAttribute
import bosca.content.collaboration.CollaborationAttributeWriter
import bosca.content.collaboration.CollaborationParent
import bosca.content.collaboration.CollaborationRelationship
import bosca.content.metadata.model.BibleInput
import bosca.content.metadata.model.DataCollaborationInput
import bosca.content.metadata.model.DataInput
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideInput
import bosca.content.metadata.model.GuideStep
import bosca.content.metadata.model.GuideStepInput
import bosca.content.metadata.model.GuideStepModule
import bosca.content.metadata.model.GuideStepModuleInput
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataCacheKeySerializer
import bosca.content.metadata.model.MetadataCategory
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataPermission
import bosca.content.metadata.model.MetadataProfile
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.content.metadata.model.MetadataSupplementary
import bosca.content.metadata.model.MetadataSupplementaryInput
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.model.SourceStatus
import bosca.content.metadata.model.MetadataWorkflowPlan
import bosca.content.metadata.repository.MetadataCategoryRepository
import bosca.content.metadata.repository.MetadataFindRepository
import bosca.content.metadata.repository.MetadataPermissionRepository
import bosca.content.metadata.repository.MetadataProfileRepository
import bosca.content.metadata.repository.MetadataRelationshipRepository
import bosca.content.metadata.repository.MetadataRepository
import bosca.content.metadata.repository.MetadataSupplementaryRepository
import bosca.content.metadata.repository.MetadataTraitRepository
import bosca.content.metadata.repository.MetadataWorkflowPlanRepository
import bosca.content.recommendation.service.RecommendationContextClassifier
import bosca.content.transition.history.model.MetadataTransitionHistory
import bosca.content.transition.history.service.TransitionHistoryService
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.service.Transitioner
import bosca.db.afterCommit
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.jobs.ImportUrlJob
import bosca.jobs.enqueue
import bosca.di.annotation.InternalDI
import bosca.di.ObjectProvider
import bosca.di.provideProvider
import bosca.graphql.Batch
import bosca.graphql.BatchMapper
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionInput
import bosca.security.model.Principal
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.JsonConverter.toJsonElement
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.slug.model.Slug
import bosca.slug.service.SlugService
import bosca.content.video.service.VideoService
import bosca.db.connectionOrNull
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.upload
import bosca.trait.model.Trait
import bosca.trait.service.TraitService
import bosca.server.content.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

@ServiceImplementation
class MetadataServiceImpl(
    private val repository: MetadataRepository,
    private val collectionService: CollectionService,
    private val collectionTemplateService: CollectionTemplateService,
    private val find: ObjectProvider<MetadataFindRepository>,
    private val permissions: MetadataPermissionRepository,
    private val traits: MetadataTraitRepository,
    private val categories: MetadataCategoryRepository,
    private val plans: MetadataWorkflowPlanRepository,
    private val profiles: MetadataProfileRepository,
    private val supplementary: MetadataSupplementaryRepository,
    private val relationships: MetadataRelationshipRepository,
    private val transitionHistory: TransitionHistoryService,
    private val documentService: DocumentService,
    private val documentTemplateService: DocumentTemplateService,
    private val dataTemplateService: DataTemplateService,
    private val guideService: GuideService,
    private val guideTemplateService: GuideTemplateService,
    private val dataService: DataService,
    private val bibleService: BibleService,
    private val objectService: ObjectStorageService,
    private val slugService: SlugService,
    private val traitService: TraitService,
    private val categoriesService: CategoryService,
    private val securityService: ObjectProvider<SecurityService>,
    private val transitioner: ObjectProvider<Transitioner>,
    private val videoService: VideoService,
) : MetadataService {

    private suspend fun classifyRecommendationContexts(
        contentType: String?,
        attributes: JsonElement?,
        fallback: List<String> = emptyList(),
    ): List<String> {
        val classifier = provideProvider<RecommendationContextClassifier>()
        return if (classifier.exists) classifier.get().classifyMetadata(contentType, attributes) else fallback
    }

    private val metadataCacheById = ServiceCache(
        "metadata",
        MetadataCacheKeySerializer,
        { keys, batch ->
            val metadatas = repository.getByIds(keys.map { it.id }).associateBy { it.id }
            keys.forEach {
                batch.setData(it, metadatas[it.id] ?: return@forEach)
            }
        }
    ) {
        repository.getById(it.id)
    }

    private val metadataCategoryCache = ServiceCache(
        cacheName = "metadata:category:id",
        MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val categories = categories.getMetadataCategoryByMetadataIds(keys.map { it.id })
            val categoriesByMetadataId = categories.groupBy { it.metadataId }
            val categoriesById = categoriesService.getAll(categories.map { it.categoryId }).associateBy { it.id }
            keys.forEach {
                val metadataCategories = categoriesByMetadataId[it.id] ?: return@forEach
                val traits = metadataCategories.mapNotNull { categoriesById[it.categoryId] }
                batch.setData(it, traits)
            }
        }
    ) {
        val categories = categories.getMetadataCategoryByMetadataId(it.id)
        val categoriesById = categoriesService.getAll(categories.map { it.categoryId }).associateBy { it.id }
        categories.mapNotNull {
            categoriesById[it.categoryId]
        }
    }

    private val metadataTraitCache = ServiceCache(
        cacheName = "metadata:trait:id",
        MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val traits = traits.getMetadataTraitsByMetadataIds(keys.map { it.id })
            val traitsByMetadataId = traits.groupBy { it.metadataId }
            val traitsById = traitService.getAll(traits.map { it.traitId }).associateBy { it.id }
            keys.forEach {
                val metadataTraits = traitsByMetadataId[it.id] ?: return@forEach
                val traits = metadataTraits.mapNotNull { traitsById[it.traitId] }
                batch.setData(it, traits)
            }
        }
    ) {
        val metadataTraits = traits.getMetadataTraitsByMetadataId(it.id)
        traitService.getAll(metadataTraits.map { it.traitId })
    }

    private val metadataTraitIdCache = ServiceCache("metadata:trait:id:version", MetadataCacheKeySerializer, { keys, batch ->
        val results = traits.getMetadataTraitsByMetadataIds(keys.map { it.id }).groupBy { MetadataCacheKeyId(it.metadataId) }
        for (key in keys) {
            batch.setData(key, results[key]?.map { it.traitId } ?: emptyList())
        }
    }) {
        // TODO: use version
        traits.getMetadataTraitsByMetadataId(it.id).map { it.traitId }
    }

    private val metadataCacheByIdVersion = ServiceCache("metadata:version", MetadataCacheKeySerializer) {
        // TODO: support lookup by version
        repository.getById(it.id)
    }

    private val metadataProfiles = ServiceCache("metadata:profiles", MetadataCacheKeySerializer) {
        profiles.getByMetadataId(it.id)
    }

    private val metadataPermissions = ServiceCache<UUID, List<EntityPermission>>(
        "metadata:permissions",
        UUIDKeySerializer,
        { keys, batch ->
            val permissionsByGroupId = permissions.getMetadataPermissionsByMetadataIds(keys).groupBy { it.metadataId }
            keys.forEach {
                batch.setData(it, permissionsByGroupId[it] ?: return@forEach)
            }
        }
    ) {
        permissions.getMetadataPermissionsByMetadataId(it)
    }

    private val metadataRelationships = ServiceCache(
        "metadata:relationships",
        MetadataCacheKeySerializer,
        { keys, batch ->
            val relsByGroupId = relationships.getByMetadataId1Batch(keys.map { it.id }).groupBy { it.metadataId1 }
            keys.forEach {
                batch.setData(it, relsByGroupId[it.id] ?: return@forEach)
            }
        }
    ) {
        relationships.getByMetadataId1(it.id)
    }

    private val metadataSupplementary = ServiceCache(
        "metadata:supplementary",
        MetadataCacheKeySerializer,
        batchResolver = { keys, batch ->
            val supplementaryById = supplementary.getByMetadataIds(keys.map { it.id }).groupBy { MetadataCacheKeyId(it.metadataId) }
            keys.forEach {
                val value = supplementaryById[it] ?: supplementaryById[MetadataCacheKeyId(it.id)] ?: return@forEach
                batch.setData(it, value)
            }
        }
    ) {
        supplementary.getByMetadataId(it.id)
    }

    private val metadataSupplementaryByKey = ServiceCache("metadata:supplementary:key", MetadataCacheKeySerializer) {
        supplementary.getByMetadataIdAndKey(it.id, it.key ?: error("missing key"))
    }

    private val metadataPrefixCaches = listOf(
        metadataCacheById,
        metadataCategoryCache,
        metadataTraitCache,
        metadataProfiles,
        metadataRelationships,
        metadataSupplementaryByKey,
        metadataSupplementary,
        metadataCacheByIdVersion,
        metadataTraitIdCache
    )

    override suspend fun removeFromCache(metadata: Metadata) {
        removeFromCache(metadata.id, metadata.version)
    }

    override suspend fun removeFromCache(id: UUID, version: Int?) {
        val cacheId = MetadataCacheKeyId(id)
        metadataPermissions.remove(id)
        guideService.removeFromCache(id, version)
        documentService.removeFromCache(id, version)
        for (cache in metadataPrefixCaches) {
            cache.remove(cacheId, keyPrefix = true)
        }
    }

    override suspend fun getAll(offset: Long, limit: Int): List<Metadata> {
        return repository.getAll(offset, limit)
    }

    override suspend fun getDeleted(offset: Long, limit: Int): List<Metadata> {
        return repository.getDeleted(offset, limit)
    }

    override suspend fun getById(id: UUID) = metadataCacheById.get(MetadataCacheKeyId(id))

    override suspend fun setName(id: UUID, name: String): Metadata? {
        val metadata = repository.setName(id, name) ?: return null
        removeFromCache(metadata)
        MetadataUpdated(metadata).dispatch()
        return metadata
    }

    override suspend fun getLanguageVariantById(id: UUID, languageTag: String): UUID? {
        return repository.getLanguageVariantById(id, languageTag)
    }

    override suspend fun getByParentId(id: UUID): List<Metadata> {
        val metadata = getById(id) ?: return emptyList()
        metadata.parentId?.let {
            return repository.getByParentId(it)
        }
        return repository.getByParentId(id)
    }

    override suspend fun getById(id: UUID, version: Int?) = metadataCacheByIdVersion.get(MetadataCacheKeyId(id, version))

    override suspend fun getByIds(ids: List<UUID>): List<Metadata> = repository.getByIds(ids)

    override suspend fun setRecommendationContexts(
        id: UUID,
        contextTypes: List<String>,
    ) {
        repository.setRecommendationContexts(id, contextTypes)
        removeFromCache(id)
    }

    override suspend fun getByIdBatched(batch: Batch<MetadataCacheKeyId, Metadata>) {
        metadataCacheById.addToBatch(batch)
    }

    override suspend fun getCategories(id: UUID): List<Category> {
        return metadataCategoryCache.get(MetadataCacheKeyId(id)) ?: emptyList()
    }

    override suspend fun addCategoriesToBatch(batch: Batch<MetadataCacheKeyId, List<Category>>) {
        metadataCategoryCache.addToBatch(batch)
    }

    override suspend fun addCategoryIdsToBatch(batch: Batch<MetadataCacheKeyId, List<UUID>>) {
        metadataCategoryCache.addToBatch(BatchMapper(batch) {
            it?.map { it.id } ?: emptyList()
        })
    }

    override suspend fun addTraitsToBatch(batch: Batch<MetadataCacheKeyId, List<Trait>>) {
        metadataTraitCache.addToBatch(batch)
    }

    override suspend fun addTraitIdsToBatch(batch: Batch<MetadataCacheKeyId, List<String>>) = metadataTraitIdCache.addToBatch(batch)

    override suspend fun getTraitIds(id: UUID): List<String> = metadataTraitIdCache.get(MetadataCacheKeyId(id)) ?: emptyList()

    override suspend fun getProfiles(id: UUID) = metadataProfiles.get(MetadataCacheKeyId(id)) ?: emptyList()

    override suspend fun getPermissions(entity: Metadata): List<EntityPermission> {
        return metadataPermissions.get(entity.id) ?: emptyList()
    }

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        metadataPermissions.addToBatch(batch)
    }

    override suspend fun setParent(id: UUID, parentId: UUID?) = transaction {
        repository.setParentId(id, parentId)
        removeFromCache(id)
        MetadataUpdated(getById(id) ?: error("missing metadata $id")).dispatch()
    }

    override suspend fun setSearchable(id: UUID, searchable: Boolean) {
        repository.setSearchable(id, searchable)
        removeFromCache(id)
        MetadataUpdated(getById(id) ?: error("missing metadata $id")).dispatch()
    }

    override suspend fun setRecommendable(id: UUID, recommendable: Boolean) {
        repository.setRecommendable(id, recommendable)
        removeFromCache(id)
        MetadataUpdated(getById(id) ?: error("missing metadata $id")).dispatch()
    }

    override suspend fun setCommentsEnabled(id: UUID, enabled: Boolean) {
        repository.setCommentsEnabled(id, enabled)
        removeFromCache(id)
        MetadataUpdated(getById(id) ?: error("missing metadata $id")).dispatch()
    }

    override suspend fun setCommentRepliesEnabled(id: UUID, enabled: Boolean) {
        repository.setCommentRepliesEnabled(id, enabled)
        removeFromCache(id)
        MetadataUpdated(getById(id) ?: error("missing metadata $id")).dispatch()
    }

    override suspend fun setSyncVariantRelationships(id: UUID, syncVariants: Boolean) {
        repository.setSyncVariantRelationships(id, syncVariants)
        removeFromCache(id)
        MetadataUpdated(getById(id) ?: error("missing metadata $id")).dispatch()
    }

    override suspend fun setSyncVariantCollections(id: UUID, syncVariants: Boolean) {
        repository.setSyncVariantCollections(id, syncVariants)
        removeFromCache(id)
        MetadataUpdated(getById(id) ?: error("missing metadata $id")).dispatch()
    }

    override suspend fun add(parent: Collection?, collectionItemAttributes: JsonElement?, input: MetadataInput): Metadata = transaction {
        if (parent?.id != input.parentCollectionId) {
            error("Parent collection id does not match")
        }
        val newMetadata = input.toMetadata("pending")
        var metadata = repository.add(
            newMetadata.copy(
                recommendationContexts = classifyRecommendationContexts(
                    newMetadata.contentType,
                    newMetadata.attributes,
                ),
            ),
        )
        val id = metadata.id
        metadata = repository.getById(id) ?: error("missing metadata")
        input.categoryIds?.forEach {
            categories.add(MetadataCategory(id, it))
        }
        input.traitIds?.forEach { traits.addTrait(id, it) }
        input.profiles?.forEachIndexed { index, profile ->
            profiles.add(MetadataProfile(id, profile.profileId, profile.relationship, index))
        }
        input.document?.let {
            documentService.addDocument(id, metadata.version, it)
        }
        input.data?.let {
            dataService.addData(id, metadata.version, it)
        }
        input.guide?.let {
            val guide = it.copy(
                steps = it.steps.map { step ->
                    val metadata = step.metadata?.let { add(null, null, it) }
                    step.copy(
                        metadata = null,
                        stepMetadataId = metadata?.id ?: step.stepMetadataId,
                        stepMetadataVersion = metadata?.version ?: step.stepMetadataVersion,
                        modules = step.modules.map { module ->
                            val metadata = module.metadata?.let { add(null, null, it) }
                            module.copy(
                                metadata = null,
                                moduleMetadataId = metadata?.id ?: module.moduleMetadataId,
                                moduleMetadataVersion = metadata?.version ?: module.moduleMetadataVersion,
                            )
                        }
                    )
                }
            )
            guideService.addGuide(id, metadata.version, guide)
        }
        input.collectionTemplate?.let {
            collectionTemplateService.saveTemplate(id, metadata.version, it)
        }
        input.guideTemplate?.let {
            guideTemplateService.saveTemplate(id, metadata.version, it)
        }
        input.documentTemplate?.let {
            documentTemplateService.saveTemplate(id, metadata.version, it)
        }
        input.dataTemplate?.let {
            dataTemplateService.saveTemplate(id, metadata.version, it)
        }
        if (parent != null) {
            collectionService.addMetadataItem(
                parent.id,
                metadata.id,
                collectionItemAttributes
            )
            val permissions = collectionService.getPermissions(parent)
            for (permission in permissions) {
                addPermission(
                    PermissionInput(
                        entityId = metadata.id,
                        groupId = permission.groupId,
                        action = permission.action,
                    )
                )
            }
        }
        traitService.getTraitsByContentType(input.contentType).forEach {
            traits.addTrait(id, it.id)
        }

        if (input.slug.isNullOrBlank()) {
            val slug = slugService.createSlug(metadata.name)
            slugService.add(
                Slug(
                    metadataId = id,
                    slug = slug,
                )
            )
        } else {
            slugService.add(
                Slug(
                    metadataId = id,
                    slug = input.slug ?: error("missing slug"),
                )
            )
        }

        MetadataCreated(metadata).dispatch()
        // TODO: update etag
        metadata
    }

    override suspend fun setPublic(metadata: Metadata, public: Boolean) {
        repository.setPublic(metadata.id, public)
        removeFromCache(metadata)
        MetadataUpdated(metadata.copy(public = true)).dispatch()
    }

    override suspend fun setPublicContent(metadata: Metadata, public: Boolean) {
        repository.setPublicContent(metadata.id, public)
        removeFromCache(metadata)
        MetadataUpdated(metadata.copy(publicContent = public)).dispatch()
    }

    override suspend fun setPublicSupplementary(metadata: Metadata, public: Boolean) {
        repository.setPublicSupplementary(metadata.id, public)
        removeFromCache(metadata)
        MetadataUpdated(metadata.copy(publicSupplementary = public)).dispatch()
    }

    override suspend fun addPermission(permission: PermissionInput): EntityPermission {
        permissions.addPermission(permission.entityId, permission.groupId, permission.action)
        removeFromCache(permission.entityId)
        return MetadataPermission(
            metadataId = permission.entityId,
            groupId = permission.groupId,
            action = permission.action,
        )
    }

    override suspend fun deletePermission(permission: PermissionInput): EntityPermission {
        permissions.deletePermission(permission.entityId, permission.groupId, permission.action)
        removeFromCache(permission.entityId)
        return MetadataPermission(
            metadataId = permission.entityId,
            groupId = permission.groupId,
            action = permission.action,
        )
    }

    override suspend fun setDocumentTemplate(metadata: Metadata, templateId: UUID, templateVersion: Int) {
        documentService.setDocumentTemplate(metadata, templateId, templateVersion)
        val template = documentTemplateService.getTemplate(templateId, templateVersion) ?: error("missing template")
        template.defaultAttributes?.takeIf { it is JsonObject }?.let { attributes ->
            val updatedAttributes = JsonObject((metadata.attributes?.takeIf { it is JsonObject }?.jsonObject ?: JsonObject(emptyMap())) + attributes.jsonObject)
            repository.setAttributes(
                metadata.id,
                updatedAttributes,
                classifyRecommendationContexts(
                    metadata.contentType,
                    updatedAttributes,
                    metadata.recommendationContexts,
                ),
            )
            markCollaborationAttributesDirty(metadata.id)
        }
        removeFromCache(metadata.id)
        MetadataUpdated(metadata).dispatch()
    }

    override suspend fun setDataTemplate(metadata: Metadata, templateId: UUID, templateVersion: Int) {
        dataService.setTemplate(metadata.id, metadata.version, templateId, templateVersion)
        val template = dataTemplateService.getTemplate(templateId, templateVersion) ?: error("missing template")
        template.defaultAttributes?.takeIf { it is JsonObject }?.let { attributes ->
            val updatedAttributes = JsonObject((metadata.attributes?.takeIf { it is JsonObject }?.jsonObject ?: JsonObject(emptyMap())) + attributes.jsonObject)
            repository.setAttributes(
                metadata.id,
                updatedAttributes,
                classifyRecommendationContexts(
                    metadata.contentType,
                    updatedAttributes,
                    metadata.recommendationContexts,
                ),
            )
            markCollaborationAttributesDirty(metadata.id)
        }
        removeFromCache(metadata.id)
        MetadataUpdated(metadata).dispatch()
    }

    override suspend fun addDocument(parentCollectionId: UUID?, template: Metadata, title: String?, contentType: String?): Metadata = transaction {
        if (template.contentType != "bosca/v-document-template") throw IllegalArgumentException("Invalid Template Content Type")
        val documentTemplate = documentTemplateService.getTemplate(
            template.id,
            template.version
        ) ?: error("missing template")
        @Suppress("UNCHECKED_CAST")
        val documentTemplateAttrs = documentTemplate.defaultAttributes as Map<String, Any>? ?: emptyMap()
        val attributes = mutableMapOf<String, Any?>()
        attributes["editor.type"] = documentTemplateAttrs["editor.type"]
        for (attr in documentTemplateAttrs) {
            attributes[attr.key] = attr.value
        }
        val categoryIds = categories.getMetadataCategoryByMetadataId(template.id)
        val collection = parentCollectionId?.let { collectionService.getById(it) }
        add(
            collection,
            null,
            MetadataInput(
                parentCollectionId = parentCollectionId,
                name = title ?: "New Document",
                metadataType = MetadataType.STANDARD,
                contentType = contentType ?: "bosca/v-document",
                contentLength = null,
                languageTag = template.languageTag,
                attributes = attributes.toJsonElement(),
                document = DocumentInput(
                    templateMetadataId = template.id,
                    templateMetadataVersion = template.version,
                    title = title ?: "New Document",
                    content = documentTemplate.content
                ),
                categoryIds = categoryIds.map { it.categoryId }
            )
        )
    }

    override suspend fun addData(parentCollectionId: UUID?, template: Metadata): Metadata = transaction {
        if (template.contentType != "bosca/v-data-template") throw IllegalArgumentException("Invalid Template Content Type")
        val dataTemplate = dataTemplateService.getTemplate(
            template.id,
            template.version
        ) ?: error("missing template")
        @Suppress("UNCHECKED_CAST")
        val dataTemplateAttrs = dataTemplate.defaultAttributes as Map<String, Any>? ?: emptyMap()
        val attributes = mutableMapOf<String, Any?>()
        attributes["editor.type"] = dataTemplateAttrs["editor.type"]
        for (attr in dataTemplateAttrs) {
            attributes[attr.key] = attr.value
        }
        val categoryIds = categories.getMetadataCategoryByMetadataId(template.id)
        val collection = parentCollectionId?.let { collectionService.getById(it) }
        add(
            collection,
            null,
            MetadataInput(
                parentCollectionId = parentCollectionId,
                name = "New Data",
                metadataType = MetadataType.STANDARD,
                contentType = "bosca/v-data",
                contentLength = null,
                languageTag = template.languageTag,
                attributes = attributes.toJsonElement(),
                data = DataInput(
                    templateMetadataId = template.id,
                    templateMetadataVersion = template.version,
                    type = dataTemplate.type
                ),
                categoryIds = categoryIds.map { it.categoryId }
            )
        )
    }

    override suspend fun setDocument(
        metadata: Metadata,
        document: DocumentInput,
        collaborationSync: CollaborationSyncMode,
    ) = transaction {
        documentService.setDocument(metadata, document, collaborationSync)
        repository.setModified(metadata.id)
        removeFromCache(metadata)
        MetadataUpdated(metadata).dispatch()
    }

    override suspend fun setBible(metadata: Metadata, bible: BibleInput) = transaction {
        bibleService.setBible(metadata.id, metadata.version, bible)
        repository.setModified(metadata.id)
        removeFromCache(metadata)
        MetadataUpdated(metadata).dispatch()
    }

    override suspend fun setBibleVariantEnabled(metadata: Metadata, variant: String, enabled: Boolean) = transaction {
        val bible = bibleService.setVariantEnabled(metadata.id, metadata.version, variant, enabled)
        repository.setModified(metadata.id)
        removeFromCache(metadata)
        MetadataUpdated(metadata).dispatch()
        bible
    }

    override suspend fun setDefaultBibleVariant(metadata: Metadata, variant: String) = transaction {
        val bible = bibleService.setDefaultVariant(metadata.id, metadata.version, variant)
        repository.setModified(metadata.id)
        removeFromCache(metadata)
        MetadataUpdated(metadata).dispatch()
        bible
    }

    override suspend fun setAttributes(metadata: Metadata, attributes: JsonElement) = transaction {
        if (attributes is JsonArray) error("must be an object")
        repository.setAttributes(
            metadata.id,
            attributes,
            classifyRecommendationContexts(metadata.contentType, attributes, metadata.recommendationContexts),
        )
        removeFromCache(metadata)
        syncDataCollaboration(metadata.id, metadata.version)
        MetadataUpdated(metadata).dispatch()
    }

    override suspend fun mergeAttributes(metadata: Metadata, attributes: JsonElement) = transaction {
        if (attributes is JsonArray) error("must be an object")
        val current = repository.getAttributes(metadata.id)?.takeIf { it is JsonObject } ?: JsonObject(emptyMap())
        val newAttrs = JsonObject(current.jsonObject + attributes.jsonObject)
        if (current == newAttrs) return@transaction
        repository.setAttributes(
            metadata.id,
            newAttrs,
            classifyRecommendationContexts(metadata.contentType, newAttrs, metadata.recommendationContexts),
        )
        removeFromCache(metadata)
        syncDataCollaboration(metadata.id, metadata.version)
        MetadataUpdated(metadata).dispatch()
    }

    override suspend fun addGuide(parentCollectionId: UUID?, template: Metadata): Metadata = transaction {
        if (template.contentType != "bosca/v-guide-template") throw IllegalArgumentException("Invalid Template Content Type")
        val guideTemplate = guideTemplateService.getTemplate(
            template.id,
            template.version
        ) ?: error("missing template")
        val templateSteps = guideTemplateService.getTemplateSteps(template.id, template.version).firstOrNull()

        @Suppress("UNCHECKED_CAST")
        val guideTemplateAttrs = guideTemplate.defaultAttributes as Map<String, Any>? ?: emptyMap()
        val attributes = mutableMapOf<String, Any?>()
        val step = templateSteps?.let {
            getById(
                it.templateMetadataId ?: error("missing templateMetadataId"),
                it.templateMetadataVersion ?: error("missing templateMetadataVersion")
            )?.let {
                val metadata = addDocument(null, it, "New Step", "bosca/v-guide-step")
                GuideStepInput(
                    modules = emptyList(),
                    stepMetadataId = metadata.id,
                    stepMetadataVersion = metadata.version,
                )
            }
        }
        attributes["editor.type"] = guideTemplateAttrs["editor.type"]
        val documentTemplate = documentTemplateService.getTemplate(
            template.id,
            template.version
        ) ?: error("missing template")
        for (attr in documentTemplate.defaultAttributes as Map<*, *>) {
            attributes[attr.key.toString()] = attr.value
        }
        for (attr in guideTemplateAttrs) {
            attributes[attr.key] = attr.value
        }
        val categoryIds = categories.getMetadataCategoryByMetadataId(template.id)
        val collection = parentCollectionId?.let { collectionService.getById(it) }
        add(
            collection,
            null,
            MetadataInput(
                parentCollectionId = parentCollectionId,
                name = "New Guide",
                metadataType = MetadataType.STANDARD,
                contentType = "bosca/v-guide",
                contentLength = null,
                languageTag = template.languageTag,
                attributes = attributes.toJsonElement(),
                document = DocumentInput(
                    templateMetadataId = template.id,
                    templateMetadataVersion = template.version,
                    title = "New Document",
                    content = documentTemplate.content
                ),
                guide = GuideInput(
                    templateMetadataId = template.id,
                    templateMetadataVersion = template.version,
                    guideType = guideTemplate.type,
                    rrule = guideTemplate.rrule,
                    steps = step?.let { listOf(it) } ?: emptyList()
                ),
                categoryIds = categoryIds.map { it.categoryId }
            )
        )
    }

    override suspend fun addGuideStep(guide: Guide, templateStepId: Long, index: Int): GuideStep = transaction {
        val title = if (index == 0) {
            "New Step"
        } else {
            "New Step ${index + 1}"
        }
        val templateStep = guideTemplateService.getTemplateStep(
            guide.templateMetadataId ?: error("missing template metadata id"),
            guide.templateMetadataVersion ?: error("missing template metadata version"),
            templateStepId
        ) ?: error("missing template step")

        val templateStepDocument = getById(
            templateStep.templateMetadataId ?: error("missing template metadata id"),
            templateStep.templateMetadataVersion ?: error("missing template metadata version")
        ) ?: error("missing template step document")
        val steps = guideService.getGuideSteps(guide.metadataId, guide.version)
        val guideStepIds = steps.map { it.id }
        var cur = index
        while (cur < guideStepIds.size) {
            val id = guideStepIds[cur]
            cur++
            guideService.setGuideStepSort(guide.metadataId, guide.version, id, cur)
        }
        val (stepMetadataId, stepMetadataVersion) = addDocument(null, templateStepDocument, title, "bosca/v-guide-step")
        removeFromCache(guide.metadataId, guide.version)
        guideService.addGuideStep(
            guide.metadataId,
            guide.version,
            GuideStepInput(
                stepMetadataId,
                stepMetadataVersion,
                metadata = null,
                modules = emptyList()
            ),
            index
        )
    }

    override suspend fun addGuideStepModule(guide: Guide, stepId: Long, templateModuleId: Long, index: Int): GuideStepModule = transaction {
        val title = if (index == 0) {
            "New Module"
        } else {
            "New Module ${index + 1}"
        }
        // stepId is the guide step's identifier, which is unrelated to template step
        // identifiers — resolve the template module by its globally unique id instead.
        val templateStepModule = guideTemplateService.getTemplateModule(
            guide.templateMetadataId ?: error("missing template metadata id"),
            guide.templateMetadataVersion ?: error("missing template metadata version"),
            templateModuleId
        ) ?: error("missing template module: $templateModuleId")
        val templateStepModuleDocument = getById(
            templateStepModule.templateMetadataId ?: error("missing template metadata id"),
            templateStepModule.templateMetadataVersion ?: error("missing template metadata version")
        ) ?: error("missing template step document")
        val modules = guideService.getGuideStepModules(guide.metadataId, guide.version, stepId)
        val guideStepModuleIds = modules.map { it.id }
        var cur = index
        while (cur < guideStepModuleIds.size) {
            val id = guideStepModuleIds[cur]
            cur++
            guideService.setGuideStepSort(guide.metadataId, guide.version, id, cur)
        }
        val (moduleMetadataId, moduleMetadataVersion) = addDocument(null, templateStepModuleDocument, title, "bosca/v-guide-step-module")
        removeFromCache(guide.metadataId, guide.version)
        guideService.addGuideStepModule(
            guide.metadataId,
            guide.version,
            stepId,
            GuideStepModuleInput(
                moduleMetadataId = moduleMetadataId,
                moduleMetadataVersion = moduleMetadataVersion
            ),
            index
        )
    }

    override suspend fun edit(id: UUID, input: MetadataInput): Metadata = transaction {
        val existing = repository.getById(id) ?: throw NoSuchElementException("Metadata not found: $id")
        val candidate = input.toMetadata(existing)
        val updated = candidate.copy(
            recommendationContexts = classifyRecommendationContexts(
                candidate.contentType,
                candidate.attributes,
                existing.recommendationContexts,
            ),
        )
        val saved = repository.edit(updated)
        input.categoryIds?.let {
            categories.deleteByMetadataId(id)
            it.forEach { categories.add(MetadataCategory(id, it)) }
        }
        input.traitIds?.let {
            traits.deleteByMetadataId(id)
            it.forEach { traits.addTrait(id, it) }
        }
        input.document?.let {
            documentService.setDocument(saved, it)
        }
        input.data?.let {
            dataService.setData(saved, it)
        }
        input.documentTemplate?.let {
            documentTemplateService.saveTemplate(id, saved.version, it)
        }
        input.dataTemplate?.let {
            dataTemplateService.saveTemplate(id, saved.version, it)
        }
        input.collectionTemplate?.let {
            collectionTemplateService.saveTemplate(id, saved.version, it)
        }
        traits.deleteByMetadataId(id)
        traitService.getTraitsByContentType(input.contentType).forEach {
            traits.addTrait(id, it.id)
        }
        if (!input.slug.isNullOrBlank()) {
            slugService.deleteMetadataSlug(saved.id)
            slugService.add(
                Slug(
                    metadataId = id,
                    slug = input.slug ?: error("missing slug"),
                )
            )
        }
        removeFromCache(existing)
        syncDataCollaboration(saved.id, saved.version)
        MetadataUpdated(saved).dispatch()
        saved
    }

    /**
     * Re-encodes a data metadata's attribute state into its data collaboration Yjs
     * document. The attribute editor reads values from that document — it is what
     * powers the attribute UI — so it must stay consistent with the persisted
     * attributes after a server-side change.
     *
     * Runs within the caller's transaction: a failure rolls back the whole
     * operation, so the persisted attributes and the collaboration document never
     * diverge. No-op unless a data collaboration document exists — which excludes
     * document/guide metadata (those collaborate live through their Hocuspocus
     * provider) and any metadata never opened in the data editor.
     */
    private suspend fun syncDataCollaboration(metadataId: UUID, version: Int) {
        val existing = dataService.getCollaboration(metadataId, version) ?: return
        val metadata = repository.getById(metadataId, version) ?: return
        val data = dataService.getData(metadataId, version) ?: return
        val templateId = data.templateMetadataId ?: return
        val templateVersion = data.templateMetadataVersion ?: return
        val templateAttributes = dataTemplateService.getTemplateAttributes(templateId, templateVersion)
            .map { CollaborationAttribute(it.key, it.type, it.list, it.location, it.configuration) }
        if (templateAttributes.isEmpty()) return

        val rels = relationships.getByMetadataId1(metadataId)
        val collaborationRelationships = if (rels.isEmpty()) emptyList() else {
            val relatedById = repository.getByIds(rels.map { it.metadataId2 }.distinct()).associateBy { it.id }
            rels.map { rel ->
                val related = relatedById[rel.metadataId2]
                CollaborationRelationship(
                    metadataId = rel.metadataId2,
                    relationship = rel.relationship,
                    attributes = rel.attributes,
                    name = related?.name ?: "",
                    contentType = related?.contentType ?: "",
                )
            }
        }
        val collaborationParents = collectionService.getMetadataParents(metadataId)
            .map { CollaborationParent(it.id, it.name, it.attributes) }

        val content = CollaborationAttributeWriter.apply(
            existing.content,
            templateAttributes,
            metadata.attributes,
            collaborationRelationships,
            collaborationParents,
        )
        dataService.setCollaboration(DataCollaborationInput(metadataId, version, content))
    }

    override suspend fun setUploaded(id: UUID, contentType: String?, contentLength: Long) {
        val metadata = getById(id) ?: error("missing metadata")
        val resolvedContentType = contentType ?: "application/octet-stream"
        repository.setUploaded(
            id,
            resolvedContentType,
            contentLength,
            classifyRecommendationContexts(
                resolvedContentType,
                metadata.attributes,
                metadata.recommendationContexts,
            ),
        )
        removeFromCache(id)
        MetadataUploadedEvent(getById(id) ?: error("missing metadata")).dispatch()
    }

    override suspend fun setEmbeddings(metadata: Metadata, embeddings: List<EmbeddingChunk>): Boolean {
        val ordered = embeddings.sortedBy { it.index }
        require(ordered.map { it.index } == ordered.indices.toList()) {
            "Embedding chunk indexes must be contiguous and start at zero"
        }
        require(ordered.all {
            it.tokenStart >= 0 && it.tokenEnd > it.tokenStart && it.tokenCount == it.tokenEnd - it.tokenStart
        }) {
            "Embedding chunk token spans must be positive and match token counts"
        }
        require(ordered.firstOrNull()?.tokenStart in listOf(null, 0)) {
            "Embedding chunk token coverage must start at zero"
        }
        require(ordered.zipWithNext().all { (previous, next) ->
            next.tokenStart > previous.tokenStart &&
                next.tokenStart <= previous.tokenEnd &&
                next.tokenEnd > previous.tokenEnd
        }) {
            "Embedding chunk token spans must be ordered, overlapping or adjacent, and gap-free"
        }
        require(ordered.all { it.aggregationWeight.isFinite() && it.aggregationWeight > 0.0 }) {
            "Embedding chunk aggregation weights must be finite and positive"
        }
        val uniqueTokenCount = ordered.lastOrNull()?.tokenEnd?.toDouble() ?: 0.0
        val weightTolerance = maxOf(1e-6, uniqueTokenCount * 1e-9)
        require(kotlin.math.abs(ordered.sumOf { it.aggregationWeight } - uniqueTokenCount) <= weightTolerance) {
            "Embedding chunk aggregation weights must total the unique source-token count"
        }
        require(ordered.all { chunk -> chunk.embedding.isNotEmpty() && chunk.embedding.all(Float::isFinite) }) {
            "Embedding chunk vectors must be non-empty and finite"
        }
        require(ordered.map { it.embedding.size }.distinct().size <= 1) {
            "Embedding chunk vectors must all have the same dimension"
        }
        val chunks = buildJsonArray {
            ordered.forEach { chunk ->
                add(buildJsonObject {
                    put("chunk_index", chunk.index)
                    put("token_start", chunk.tokenStart)
                    put("token_end", chunk.tokenEnd)
                    put("token_count", chunk.tokenCount)
                    put("aggregation_weight", chunk.aggregationWeight)
                    put(
                        "embedding",
                        chunk.embedding.joinToString(prefix = "[", postfix = "]", separator = ","),
                    )
                })
            }
        }
        return transaction {
            val current = checkNotNull(repository.lockForEmbeddingReplacement(metadata.id)) {
                "metadata not found: ${metadata.id}"
            }
            // Network/tokenizer work and serialization are complete before this short transaction. The
            // no-key-update lock serializes replacements without blocking ordinary MVCC readers.
            if (current.version != metadata.version || current.modified != metadata.modified) {
                return@transaction false
            }
            repository.deleteEmbeddings(metadata.id)
            if (ordered.isNotEmpty()) repository.addEmbeddingChunks(metadata.id, chunks)
            true
        }
    }

    override suspend fun getEmbeddingIds(ids: List<UUID>): List<UUID> =
        if (ids.isEmpty()) emptyList() else repository.getEmbeddingIds(ids)

    override suspend fun setSourceStatus(id: UUID, status: SourceStatus?) {
        val metadata = repository.setSourceStatus(id, status) ?: error("metadata not found")
        removeFromCache(id)
        MetadataUpdated(metadata).dispatch()
    }

    override suspend fun importFromUrl(id: UUID, url: String, contentType: String?, ready: Boolean, principalId: UUID?) {
        setSourceStatus(id, SourceStatus.PENDING)
        // The same job the importUrl mutation rides: downloads, streams into object storage, resolves
        // the real content type, marks uploaded/IMPORTED, and optionally readies as the principal.
        ImportUrlJob(
            id = id,
            url = url,
            contentType = contentType,
            ready = ready,
            principalId = principalId,
        ).enqueue()
    }

    override suspend fun setSupplementaryUploaded(id: UUID, contentType: String?, contentLength: Long) {
        val supplementary = supplementary.setUploaded(id, contentType ?: "application/octet-stream", contentLength)
        removeFromCache(supplementary.metadataId)
        val metadata = getById(supplementary.metadataId) ?: error("missing metadata")
        MetadataSupplementedUpdatedEvent(metadata, id).dispatch()
    }

    override suspend fun setLocked(id: UUID, version: Int, locked: Boolean) {
        repository.setLocked(id, locked)
        removeFromCache(id, version)
        val metadata = getById(id) ?: error("missing metadata")
        if (locked) {
            MetadataLockedEvent(metadata).dispatch()
        } else {
            MetadataUnlockedEvent(metadata).dispatch()
        }
    }

    override suspend fun setState(item: Metadata, toStateId: String, status: String, principal: Principal?): Metadata {
        transitionHistory.add(
            MetadataTransitionHistory(
                item.id,
                item.workflowStateId,
                toStateId,
                principal?.id,
                status,
                success = true,
                complete = true
            )
        )
        repository.setState(item.id, toStateId)
        removeFromCache(item)
        MetadataStateChanged(item).dispatch()
        return getById(item.id, item.version) ?: error("missing metadata")
    }

    override suspend fun setPendingState(
        item: Metadata,
        toStateId: String,
        status: String,
        valid: OffsetDateTime?,
        principal: Principal?,
        notifyEvent: Boolean
    ): Metadata {
        transitionHistory.add(
            MetadataTransitionHistory(
                item.id,
                item.workflowStateId,
                toStateId,
                principal?.id,
                status,
                success = true,
                complete = false
            )
        )
        val metadata = repository.setStatePending(item.id, toStateId, valid)
        removeFromCache(item)
        if (notifyEvent) {
            MetadataStateChanged(item).dispatch()
        }
        return metadata
    }

    override suspend fun setPendingStateFailed(item: Metadata, status: String, principal: Principal?): Metadata {
        val pendingStateId = item.workflowStatePendingId ?: error("missing pending state id")
        transitionHistory.add(
            MetadataTransitionHistory(
                item.id,
                item.workflowStateId,
                pendingStateId,
                principal?.id,
                status,
                success = false,
                complete = true
            )
        )
        repository.setState(item.id, item.workflowStateId)
        removeFromCache(item)
        MetadataStateChanged(item).dispatch()
        return getById(item.id, item.version) ?: error("missing metadata")
    }

    override suspend fun setPendingStateComplete(item: Metadata, status: String, principal: Principal?): Metadata {
        val pendingStateId = item.workflowStatePendingId ?: error("missing pending state id")
        transitionHistory.add(
            MetadataTransitionHistory(
                item.id,
                item.workflowStateId,
                pendingStateId,
                principal?.id,
                status,
                success = true,
                complete = true
            )
        )
        val metadata = repository.setState(item.id, pendingStateId)
        removeFromCache(item)
        MetadataStateChangeComplete(item).dispatch()
        if (pendingStateId == PUBLISHED_STATE_ID) {
            notifyMetadataPublished(item.id, item.version)
        }
        return metadata
    }

    /**
     * Fire the cross-module [MetadataPublishListener]s after the publishing transaction commits, so a
     * consumer (e.g. ecommerce advancing a product's pinned version) only reacts to a publish that
     * actually persisted. Listener failures are isolated so one consumer can't block the others.
     */
    @OptIn(InternalDI::class)
    private suspend fun notifyMetadataPublished(metadataId: UUID, version: Int) {
        afterCommit {
            ProviderRegistry.findAll(MetadataPublishListener::class).forEach { provider ->
                try {
                    provider.get().onPublished(metadataId, version)
                } catch (e: Exception) {
                    log.error("metadata publish listener failed for {} v{}", metadataId, version, e)
                }
            }
        }
    }

    override suspend fun setReady(metadata: Metadata, principal: Principal): Metadata {
        if (connectionOrNull()?.inTransaction == true) {
            error("cannot set ready in a transaction")
        }
        repository.setReady(metadata.id)
        removeFromCache(metadata)
        val metadata = repository.getById(metadata.id, metadata.version) ?: error("missing metadata")
        if (metadata.workflowStateId == "pending") {
            try {
                val groups = securityService.get().getPrincipalGroups(principal.id)
                val item = transitioner.get().beginTransition(
                    ImpersonatedAuthenticationContext(principal, groups),
                    BeginTransitionInput(
                        metadataId = metadata.id,
                        version = metadata.version,
                        stateId = "processing",
                        status = "Moving from pending to processing",
                        allowProcessing = true,
                    ),
                    metadata
                )
                if (item.workflowStatePendingId == null) {
                    error("missing pending state id")
                }
            } catch (e: Exception) {
                repository.setNotReady(metadata.id)
                removeFromCache(metadata)
                throw e
            }
        } else {
            log.warn("Metadata is not in pending state, skipping processing transition: ${metadata.id}")
        }
        MetadataSetReady(metadata).dispatch()
        return metadata
    }
    override suspend fun setNotReady(metadata: Metadata) {
        repository.setNotReady(metadata.id)
        removeFromCache(metadata)
        MetadataSetNotReady(metadata.copy(ready = null)).dispatch()
    }
    override suspend fun addCategory(id: UUID, categoryId: UUID) {
        categories.add(MetadataCategory(metadataId = id, categoryId = categoryId))
        removeFromCache(id)
        val metadata = getById(id) ?: error("missing metadata")
        MetadataUpdated(metadata).dispatch()
    }
    override suspend fun deleteCategory(id: UUID, categoryId: UUID) {
        categories.deleteByMetadataId(id, categoryId)
        removeFromCache(id)
        val metadata = getById(id) ?: error("missing metadata")
        MetadataUpdated(metadata).dispatch()
    }
    override suspend fun setCategories(id: UUID, categoryId: List<UUID>) {
        categories.deleteByMetadataId(id)
        categoryId.forEach { categoryId ->
            categories.add(MetadataCategory(metadataId = id, categoryId = categoryId))
        }
        removeFromCache(id)
        val metadata = getById(id) ?: error("missing metadata")
        MetadataUpdated(metadata).dispatch()
    }

    override suspend fun addTrait(id: UUID, traitId: String) {
        traits.addTrait(id, traitId)
        removeFromCache(id)
        val metadata = getById(id) ?: error("missing metadata")
        MetadataUpdated(metadata).dispatch()
    }

    override suspend fun deleteTrait(id: UUID, traitId: String) {
        traits.removeTrait(id, traitId)
        removeFromCache(id)
        val metadata = getById(id) ?: error("missing metadata")
        MetadataUpdated(metadata).dispatch()
    }

    override suspend fun setTraits(id: UUID, traitIds: List<String>) {
        traits.deleteByMetadataId(id)
        traitIds.forEach { traitId ->
            traits.addTrait(id, traitId)
        }
        removeFromCache(id)
        val metadata = getById(id) ?: error("missing metadata")
        MetadataSetTraits(metadata).dispatch()
    }

    override suspend fun clearUploaded(id: UUID) {
        val metadata = getById(id) ?: error("missing metadata")
        repository.clearUploaded(
            id,
            classifyRecommendationContexts(null, metadata.attributes, metadata.recommendationContexts),
        )
        removeFromCache(id)
        MetadataUploadCleared(getById(id) ?: error("missing metadata")).dispatch()
    }

    override suspend fun markDeleted(id: UUID) {
        log.debug("markDeleted: {}", id)
        repository.markDeleted(id)
        removeFromCache(id)
        val metadata = getById(id) ?: error("missing metadata")
        MetadataDeleted(metadata).dispatch()
    }

    override suspend fun deleteGuide(metadata: Metadata) {
        guideService.deleteGuide(this, metadata.id, metadata.version)
        removeFromCache(metadata)
    }

    override suspend fun deleteGuideStep(metadata: Metadata, stepId: Long) {
        guideService.deleteGuideStep(this, metadata.id, metadata.version, stepId)
        removeFromCache(metadata)
    }

    override suspend fun deleteGuideStepModule(metadata: Metadata, stepId: Long, moduleId: Long) {
        guideService.deleteGuideStepModule(this, metadata.id, metadata.version, stepId, moduleId)
        removeFromCache(metadata)
    }

    override suspend fun delete(metadata: Metadata) {
        videoService.delete(metadata)
        transaction {
            repository.deleteById(metadata.id)
            removeFromCache(metadata)
            val supplementary = getSupplementary(metadata.id)
            supplementary.forEach {
                val path = objectService.getPath(metadata, it.id)
                objectService.delete(path)
            }
            val path = objectService.getPath(metadata)
            objectService.delete(path)
        }
    }
    override suspend fun setSystemAttributes(
        metadata: Metadata,
        attributes: JsonElement?
    ) {
        repository.setSystemAttributes(metadata.id, attributes)
        removeFromCache(metadata)
        MetadataUpdated(metadata.copy(systemAttributes = attributes)).dispatch()
    }

    override suspend fun getSupplementary(metadataId: UUID): List<MetadataSupplementary> = metadataSupplementary.get(MetadataCacheKeyId(metadataId)) ?: emptyList()

    override suspend fun addSupplementaryToBatch(batch: Batch<MetadataCacheKeyId, List<MetadataSupplementary>>) = metadataSupplementary.addToBatch(batch)

    override suspend fun getSupplementaryById(id: UUID): MetadataSupplementary? = supplementary.getById(id)

    override suspend fun getSupplementaryByMetadataAndKey(id: UUID, key: String) = metadataSupplementaryByKey.get(MetadataCacheKeyId(id, key = key))

    override suspend fun addSupplementary(supplementary: MetadataSupplementaryInput): MetadataSupplementary {
        if (supplementary.attributes != null && supplementary.attributes !is JsonObject) error("must be an object")
        val supplementary = this.supplementary.add(
            MetadataSupplementary(
                metadataId = supplementary.metadataId,
                key = supplementary.key,
                name = supplementary.name,
                planId = supplementary.planId,
                jobId = supplementary.jobId,
                attributes = supplementary.attributes,
                created = OffsetDateTime.now(),
                modified = OffsetDateTime.now(),
                uploaded = null,
                contentType = supplementary.contentType,
                contentLength = supplementary.contentLength,
                sourceId = supplementary.sourceId,
                sourceIdentifier = supplementary.sourceIdentifier
            )
        )
        removeFromCache(supplementary.metadataId)
        val metadata = getById(supplementary.metadataId) ?: error("missing metadata")
        MetadataSupplementedUpdatedEvent(metadata, supplementaryId = supplementary.id).dispatch()
        return supplementary
    }

    override suspend fun updateSupplementaryContent(
        metadata: Metadata,
        id: UUID,
        content: String,
        contentType: String
    ) = transaction {
        val existing = supplementary.getById(id) ?: return@transaction
        require(existing.metadataId == metadata.id) { "supplementary id does not match metadata id" }
        objectService.upload(metadata, id, content)
        val updated = existing.copy(
            contentType = contentType,
            contentLength = content.length.toLong(),
            modified = java.time.OffsetDateTime.now()
        )
        supplementary.update(updated)
        removeFromCache(metadata)
        MetadataSupplementedUpdatedEvent(metadata, supplementaryId = updated.id).dispatch()
    }

    override suspend fun updateSupplementaryContent(
        metadata: Metadata,
        id: UUID,
        file: PartData.FileItem,
        contentType: String
    ) = transaction {
        val existing = supplementary.getById(id) ?: return@transaction
        require(existing.metadataId == metadata.id) { "supplementary id does not match metadata id" }
        val length = objectService.upload(metadata, id, file)
        val updated = existing.copy(
            contentType = contentType,
            contentLength = length,
            modified = java.time.OffsetDateTime.now()
        )
        supplementary.update(updated)
        removeFromCache(metadata)
        MetadataSupplementedUpdatedEvent(metadata, supplementaryId = updated.id).dispatch()
    }

    override suspend fun setSupplementaryUploaded(
        metadata: Metadata,
        id: UUID,
        length: Long,
        contentType: String
    ) = transaction {
        val existing = supplementary.getById(id) ?: return@transaction
        require(existing.metadataId == metadata.id) { "supplementary id does not match metadata id" }
        val updated = existing.copy(
            contentType = contentType,
            contentLength = length,
            modified = java.time.OffsetDateTime.now()
        )
        supplementary.update(updated)
        removeFromCache(metadata)
        MetadataSupplementedUpdatedEvent(metadata, supplementaryId = updated.id).dispatch()
    }

    override suspend fun getRelationships(id: UUID): List<MetadataRelationship> = metadataRelationships.get(MetadataCacheKeyId(id)) ?: emptyList()

    override suspend fun addRelationshipsToBatch(batch: Batch<MetadataCacheKeyId, List<MetadataRelationship>>) {
        metadataRelationships.addToBatch(batch)
    }

    override suspend fun addRelationship(relationship: MetadataRelationshipInput): MetadataRelationship {
        return addRelationship(
            MetadataRelationship(
                metadataId1 = relationship.id1,
                metadataId2 = relationship.id2,
                relationship = relationship.relationship,
                attributes = relationship.attributes ?: JsonObject(emptyMap())
            )
        )
    }

    override suspend fun addRelationship(relationship: MetadataRelationship): MetadataRelationship {
        if (relationship.attributes !is JsonObject) error("attributes must be an object")
        val metadata = getById(relationship.metadataId1) ?: error("missing metadata: ${relationship.metadataId1}")
        val newRelationship = relationships.add(relationship)
        removeFromCache(relationship.metadataId1)
        removeFromCache(relationship.metadataId2)
        val metadata1 = getById(relationship.metadataId1)
        val metadata2 = getById(relationship.metadataId2)
        metadata1?.let { MetadataUpdated(it).dispatch() }
        metadata2?.let { MetadataUpdated(it).dispatch() }
        MetadataRelationshipAdded(metadata, relationship).dispatch()
        return newRelationship
    }

    override suspend fun mergeAttributes(id1: UUID, id2: UUID, relationship: String, attributes: JsonElement) = transaction {
        if (attributes !is JsonObject) error("must be an object")
        val attrs = relationships.getAttributes(id1, id2, relationship)?.takeIf { it is JsonObject } ?: JsonObject(mutableMapOf())
        val newAttrs = JsonObject(attrs.jsonObject + attributes.jsonObject)
        if (attrs == newAttrs) return@transaction
        relationships.setAttributeRelationships(id1, id2, relationship, newAttrs)
        removeFromCache(id1)
        removeFromCache(id2)
        val metadata1 = getById(id1)
        val metadata2 = getById(id2)
        metadata1?.let { MetadataUpdated(it).dispatch() }
        metadata2?.let { MetadataUpdated(it).dispatch() }
        MetadataRelationshipMerged(metadata1 ?: return@transaction, MetadataRelationship(id1, id2, relationship, attributes = newAttrs)).dispatch()
    }

    override suspend fun removeRelationship(id1: UUID, id2: UUID, relationship: String) {
        relationships.removeByMetadataId1AndMetadataId2AndRelationship(id1, id2, relationship)
        removeFromCache(id1)
        removeFromCache(id2)
        val metadata1 = getById(id1)
        val metadata2 = getById(id2)
        metadata1?.let { MetadataUpdated(it).dispatch() }
        metadata2?.let { MetadataUpdated(it).dispatch() }
        MetadataRelationshipRemoved(metadata1 ?: return, MetadataRelationship(id1, id2, relationship)).dispatch()
    }

    override suspend fun getParents(id: UUID): List<Collection> =
        collectionService.getMetadataParents(id)

    override suspend fun getParents(id: UUID, offset: Long, limit: Int): List<Collection> =
        collectionService.getMetadataParents(id, offset, limit)

    override suspend fun find(input: FindQueryInput) = find.get().find(input)

    override suspend fun findBySystem(input: FindQueryInput) = find.get().findBySystem(input)

    override suspend fun findCount(input: FindQueryInput) = find.get().findCount(input)

    override suspend fun getPlans(id: UUID): List<MetadataWorkflowPlan> = plans.getById(id)

    override suspend fun getBibles(id: UUID, version: Int) = bibleService.getVariants(id, version)

    override suspend fun getBible(id: UUID, version: Int, variant: String?) =
        bibleService.getBible(id, version, variant)

    override suspend fun deleteSupplementary(metadata: Metadata, id: UUID) = transaction {
        val existing = supplementary.getById(id) ?: return@transaction
        require(existing.metadataId == metadata.id) { "supplementary id does not match metadata id" }
        supplementary.deleteById(id)
        val path = objectService.getPath(metadata, id)
        objectService.delete(path)
        removeFromCache(metadata)
    }

    override suspend fun detachSupplementary(metadata: Metadata, id: UUID) = transaction {
        val existing = supplementary.getById(id) ?: return@transaction
        require(existing.metadataId == metadata.id) { "supplementary id does not match metadata id" }
        supplementary.detach(id)
        removeFromCache(metadata)
        MetadataSupplementedUpdatedEvent(metadata, supplementaryId = existing.id).dispatch()
    }

    override suspend fun markCollaborationCollectionsDirty(metadataId: UUID) {
        val metadata = getById(metadataId) ?: error("missing metadata")
        if (documentService.getDocument(metadata.id, metadata.version) != null) {
            documentService.markCollaborationCollectionsDirty(metadataId)
        }
        if (dataService.getData(metadata.id, metadata.version) != null) {
            dataService.markCollaborationCollectionsDirty(metadataId)
        }
    }

    override suspend fun markCollaborationRelationshipsDirty(metadataId: UUID) {
        val metadata = getById(metadataId) ?: error("missing metadata")
        if (documentService.getDocument(metadata.id, metadata.version) != null) {
            documentService.markCollaborationRelationshipsDirty(metadataId)
        }
        if (dataService.getData(metadata.id, metadata.version) != null) {
            dataService.markCollaborationRelationshipsDirty(metadataId)
        }
    }

    override suspend fun markCollaborationAttributesDirty(metadataId: UUID) {
        val metadata = getById(metadataId) ?: error("missing metadata")
        if (documentService.getDocument(metadata.id, metadata.version) != null) {
            documentService.markCollaborationAttributesDirty(metadataId)
        }
        if (dataService.getData(metadata.id, metadata.version) != null) {
            dataService.markCollaborationAttributesDirty(metadataId)
        }
    }

    companion object {

        /** Workflow state id a Metadata is in once published (mirrors `Metadata.isPublished`). */
        private const val PUBLISHED_STATE_ID = "published"

        private val log = LoggerFactory.getLogger(MetadataServiceImpl::class.java)
    }
}
