package bosca.content.metadata.graphql

import bosca.calendar.model.Calendar
import bosca.calendar.service.CalendarService
import bosca.category.model.Category
import bosca.content.attributes.model.AttributesFilterInput
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionTemplate
import bosca.content.metadata.model.Bible
import bosca.content.metadata.model.Data
import bosca.content.metadata.model.DataCollaboration
import bosca.content.metadata.model.DataTemplate
import bosca.content.metadata.model.Document
import bosca.content.metadata.model.DocumentCollaboration
import bosca.content.metadata.model.DocumentTemplate
import bosca.content.metadata.model.EmptyMetadata
import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.GuideTemplate
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataCacheKeyId
import bosca.content.metadata.model.MetadataContent
import bosca.content.metadata.model.Media
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.service.CollectionTemplateService
import bosca.content.metadata.service.DataService
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.DocumentTemplateService
import bosca.content.metadata.service.DataTemplateService
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.GuideTemplateService
import bosca.content.metadata.service.MediaService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventFilter
import bosca.content.timeevent.service.TimeEventService
import bosca.graphql.Batch
import bosca.graphql.BatchFilter
import bosca.graphql.BatchItem
import bosca.graphql.BatchMapper
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import bosca.trait.model.Trait

@TypeController
class MetadataController(
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val collectionTemplateService: CollectionTemplateService,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    private val profilePermissions: ProfilePermissionEvaluator,
    private val slugService: SlugService,
    private val documentService: DocumentService,
    private val documentTemplateService: DocumentTemplateService,
    private val dataTemplateService: DataTemplateService,
    private val guideService: GuideService,
    private val guideTemplateService: GuideTemplateService,
    private val profileService: ProfileService,
    private val dataService: DataService,
    private val timeEventService: TimeEventService,
    private val mediaService: MediaService,
    private val calendarService: CalendarService,
    private val commentService: bosca.comments.service.CommentService,
) : GraphQLController<Metadata> {

    @Field
    fun id(metadata: Metadata) = metadata.id

    @Field
    fun version(metadata: Metadata) = metadata.version

    @Field
    fun name(metadata: Metadata) = metadata.name

    @Field
    fun type(metadata: Metadata) = metadata.type

    @Field
    suspend fun variants(authentication: AuthenticationContext?, metadata: Metadata): List<Metadata> {
        val variants = metadataService.getByParentId(metadata.id)
        return metadataPermissionEvaluator.filterAllowed(authentication, variants, PermissionAction.VIEW)
    }

    @Field
    fun etag(metadata: Metadata, addHeader: Boolean) = metadata.etag

    @Field
    fun languageTag(metadata: Metadata) = metadata.languageTag

    @Field
    fun source(metadata: Metadata) = MetadataSource(metadata)

    @Field
    fun workflow(metadata: Metadata) = MetadataWorkflow(metadata)

    @Field
    suspend fun profiles(authentication: AuthenticationContext, metadata: Metadata) =
        metadataService.getProfiles(metadata.id).filter {
            val profile = profileService.getById(it.profileId)
            profilePermissions.isAllowed(authentication, profile, PermissionAction.VIEW)
        }

    @Field
    fun attributes(metadata: Metadata, filter: AttributesFilterInput?): Any? {
        var filter = filter
        if (metadata.isAdvertised) {
            filter = AttributesFilterInput(
                attributes = listOf("type", "description", "published")
            )
        }
        if (filter == null) return metadata.attributes
        val attributes = metadata.attributes
        if (attributes !is Map<*, *>) return emptyMap<String, Any>()
        return filter.filter(attributes)
    }

    @Field
    fun itemAttributes(metadata: Metadata): Any? = metadata.itemAttributes

    @Field
    fun systemAttributes(metadata: Metadata): Any? = metadata.systemAttributes

    @Field
    suspend fun documentCollaboration(metadata: Metadata): DocumentCollaboration? =
        documentService.getCollaboration(metadata.id, metadata.version)

    @Field
    suspend fun dataCollaboration(metadata: Metadata): DataCollaboration? =
        dataService.getCollaboration(metadata.id, metadata.version)

    @Field
    suspend fun calendar(authentication: AuthenticationContext?, metadata: Metadata): Calendar? =
        calendarService.getCalendar(authentication, metadata.id, metadata.version)

    @Field
    suspend fun permissions(batch: Batch<MetadataCacheKeyId, List<EntityPermission>>) {
        val batchId = Batch<UUID, List<EntityPermission>>(keys = batch.keys.map { it.id })
        metadataService.addPermissionsToBatch(batchId)
        batch.setData(batchId.keys.map { MetadataCacheKeyId(it) }, batchId.getResults())
        batch.ensureNotNull(emptyList())
    }

    @Field
    fun deleted(metadata: Metadata) = metadata.deleted

    @Field
    fun locked(metadata: Metadata) = metadata.locked

    @Field
    fun public(metadata: Metadata) = metadata.public

    @Field
    fun syncVariantCollections(metadata: Metadata) = metadata.syncVariantCollections

    @Field
    fun syncVariantRelationships(metadata: Metadata) = metadata.syncVariantRelationships

    @Field
    fun searchable(metadata: Metadata) = metadata.searchable

    @Field
    fun recommendable(metadata: Metadata) = metadata.recommendable

    @Field
    fun commentsEnabled(metadata: Metadata) = metadata.commentsEnabled

    @Field
    fun commentRepliesEnabled(metadata: Metadata) = metadata.commentRepliesEnabled

    @Field
    fun publicContent(metadata: Metadata) = metadata.publicContent

    @Field
    fun publicSupplementary(metadata: Metadata) = metadata.publicSupplementary

    @Field
    fun parentId(metadata: Metadata) = metadata.parentId

    @Field
    suspend fun slug(batch: Batch<MetadataCacheKeyId, String>) {
        slugService.addMetadataSlugsToBatch(batch)
    }

    @Field
    fun created(metadata: Metadata) = metadata.created

    @Field
    fun modified(metadata: Metadata) = metadata.modified ?: metadata.created

    @Field
    fun uploaded(metadata: Metadata) = metadata.uploaded

    @Field
    fun ready(metadata: Metadata) = metadata.ready

    @Field
    fun labels(metadata: Metadata) = metadata.labels

    @Field
    suspend fun traits(batch: Batch<MetadataCacheKeyId, List<Trait>>) {
        metadataService.addTraitsToBatch(batch)
        batch.ensureNotNull(emptyList())
    }

    @Field
    suspend fun traitIds(batch: Batch<MetadataCacheKeyId, List<String>>) {
        metadataService.addTraitIdsToBatch(batch)
        batch.ensureNotNull(emptyList())
    }

    @Field
    suspend fun categoryIds(batch: Batch<MetadataCacheKeyId, List<UUID>>) {
        metadataService.addCategoryIdsToBatch(batch)
        batch.ensureNotNull(emptyList())
    }

    @Field
    suspend fun categories(batch: Batch<MetadataCacheKeyId, List<Category>>) {
        metadataService.addCategoriesToBatch(batch)
        batch.ensureNotNull(emptyList())
    }

    @Field
    suspend fun content(authentication: AuthenticationContext?, batch: Batch<MetadataCacheKeyId, MetadataContent>) {
        batch.filter = object : BatchFilter<MetadataCacheKeyId, MetadataContent> {
            override suspend fun filter(items: List<BatchItem<MetadataCacheKeyId, MetadataContent>>): List<MetadataContent?> {
                val metadata = items.map { it.data?.metadata ?: EmptyMetadata }
                val allowed = metadataPermissionEvaluator.isContentAllowed(authentication, metadata, PermissionAction.VIEW)
                return items.mapIndexed { index, item -> item.data?.copy(allowed = allowed[index]) }
            }
        }
        metadataService.getByIdBatched(BatchMapper(batch) {
            it?.let { MetadataContent(it) }
        })
    }

    // TODO: enable filtering supplementary

    @Field
    suspend fun supplementary(authentication: AuthenticationContext?, batch: Batch<MetadataCacheKeyId, List<MetadataSupplementaryContext>>) {
        batch.filter = object : BatchFilter<MetadataCacheKeyId, List<MetadataSupplementaryContext>> {
            override suspend fun filter(items: List<BatchItem<MetadataCacheKeyId, List<MetadataSupplementaryContext>>>): List<List<MetadataSupplementaryContext>?> {
                val relationships = items.flatMap { it.data ?: emptyList() }
                val relationshipMetadata = Batch<MetadataCacheKeyId, Metadata>(keys = relationships.map { MetadataCacheKeyId(it.metadata.id) })
                metadataService.getByIdBatched(relationshipMetadata)
                val entities = relationshipMetadata.getResults().filterNotNull()
                val allowed = metadataPermissionEvaluator.isSupplementaryAllowed(authentication, entities, PermissionAction.VIEW)
                var ix = 0
                return items.map {
                    it.data?.takeIf { allowed[ix++] } ?: emptyList()
                }
            }
        }
        metadataService.addSupplementaryToBatch(BatchMapper(batch) {
            val metadataIds = it?.map { it.metadataId } ?: emptyList()
            val metadatas = metadataService.getByIds(metadataIds).associateBy { it.id }
            it?.map {
                MetadataSupplementaryContext(
                    metadata = metadatas[it.metadataId] ?: error("missing metadata"),
                    supplementary = it
                )
            } ?: emptyList()
        })
    }

    @Field
    suspend fun media(batch: Batch<MetadataCacheKeyId, Media>) {
        mediaService.addToBatch(batch)
    }

    @Field
    suspend fun relationships(
        authentication: AuthenticationContext?,
        batch: Batch<MetadataCacheKeyId, List<MetadataRelationship>>
    ) {
        batch.filter = object : BatchFilter<MetadataCacheKeyId, List<MetadataRelationship>> {
            override suspend fun filter(items: List<BatchItem<MetadataCacheKeyId, List<MetadataRelationship>>>): List<List<MetadataRelationship>?> {
                val relationships = items.flatMap { it.data ?: emptyList() }
                val relationshipMetadata = Batch<MetadataCacheKeyId, Metadata>(keys = relationships.map { MetadataCacheKeyId(it.metadataId2) })
                metadataService.getByIdBatched(relationshipMetadata)
                val results = relationshipMetadata.getResults().filterNotNull()
                val allowed = metadataPermissionEvaluator.isContentAllowed(authentication, results, PermissionAction.VIEW)
                val allowedMap = results.mapIndexed { index, metadata -> metadata.id to allowed[index] }.toMap()
                return items.map {
                    it.data?.filter { allowedMap[it.metadataId2] ?: false } ?: emptyList()
                }
            }
        }
        metadataService.addRelationshipsToBatch(batch)
    }

    @Field
    suspend fun parentCollections(
        authentication: AuthenticationContext?,
        metadata: Metadata,
        offset: Long?,
        limit: Int?
    ): List<Collection> {
        val parents = if (offset != null && limit != null) {
            metadataService.getParents(metadata.id, offset, limit)
        } else {
            metadataService.getParents(metadata.id)
        }
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
    suspend fun bible(metadata: Metadata, variant: String?) =
        metadataService.getBible(metadata.id, metadata.version, variant)

    @Field
    suspend fun bibles(
        authentication: AuthenticationContext?,
        metadata: Metadata,
        includeDisabled: Boolean?,
    ): List<Bible> {
        val bibles = metadataService.getBibles(metadata.id, metadata.version)
        if (includeDisabled != true) return bibles.filter { it.enabled }
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
        return bibles
    }

    @Field
    suspend fun collectionTemplate(batch: Batch<MetadataCacheKeyId, CollectionTemplate>) {
        collectionTemplateService.addCollectionTemplatesBatch(batch)
    }

    @Field
    suspend fun documentTemplate(batch: Batch<MetadataCacheKeyId, DocumentTemplate>) {
        documentTemplateService.addToBatch(batch)
    }

    @Field
    suspend fun dataTemplate(batch: Batch<MetadataCacheKeyId, DataTemplate>) {
        dataTemplateService.addToBatch(batch)
    }

    @Field
    suspend fun guideTemplate(batch: Batch<MetadataCacheKeyId, GuideTemplate>) {
        guideTemplateService.addTemplatesToBatch(batch)
    }

    @Field
    suspend fun data(batch: Batch<MetadataCacheKeyId, Data>) {
        dataService.addToBatch(batch)
    }

    @Field
    suspend fun document(batch: Batch<MetadataCacheKeyId, Document>) {
        documentService.getDocumentsBatch(batch)
    }

    @Field
    suspend fun guide(batch: Batch<MetadataCacheKeyId, Guide>) {
        guideService.addGuidesToBatch(batch)
    }

    @Field
    fun ai(metadata: Metadata) = MetadataAI(metadata)

    @Field
    suspend fun timeEvents(metadata: Metadata, filter: TimeEventFilter?): List<TimeEvent> {
        val atOffset = filter?.atOffsetMs
        val filterTypes = filter?.types
        val events = if (atOffset != null) {
            timeEventService.getTimeEventsAtOffset(metadata.id, metadata.version, atOffset)
        } else if (filterTypes != null && filterTypes.size == 1) {
            timeEventService.getTimeEventsByType(metadata.id, metadata.version, filterTypes.first())
        } else if (!filterTypes.isNullOrEmpty()) {
            // Fetch per-type and merge to avoid loading all events into memory
            filterTypes.flatMap { type ->
                timeEventService.getTimeEventsByType(metadata.id, metadata.version, type)
            }
        } else {
            timeEventService.getTimeEvents(metadata.id, metadata.version)
        }
        return events.filter { event ->
            val matchesStart = filter?.startAfterMs?.let { event.startOffsetMs >= it } ?: true
            val matchesEnd = filter?.endBeforeMs?.let { endBefore ->
                val end = event.endOffsetMs
                end != null && end <= endBefore
            } ?: true
            matchesStart && matchesEnd
        }
    }

    @Field
    suspend fun timeEventsAtOffset(metadata: Metadata, offsetMs: Long, types: List<String>?): List<TimeEvent> {
        val events = timeEventService.getTimeEventsAtOffset(metadata.id, metadata.version, offsetMs)
        return if (types != null) {
            events.filter { it.type in types }
        } else {
            events
        }
    }

    /**
     * Comments on this Metadata, paginated. A moderator (content MANAGE) sees
     * every status — including the blocked/pending ones that need a decision —
     * while everyone else sees public-approved comments plus their own. Returns
     * the long-standing `Comments` page shape (`{ comments, count }`).
     */
    @Field
    suspend fun comments(
        authentication: AuthenticationContext?,
        metadata: Metadata,
        limit: Int,
        offset: Int,
        pinned: Boolean?,
    ): bosca.comments.graphql.Comments {
        val manager = metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.MANAGE)
        val profileId = authentication?.principal()?.id?.let {
            profileService.getByPrincipal(it).firstOrNull()?.id
        }
        val pinnedOnly = pinned == true
        val list = commentService.getMetadataComments(
            profileId, metadata.id, metadata.version, manager, offset.toLong(), limit.toLong(), pinnedOnly,
        )
        val count = commentService.getMetadataCommentsCount(profileId, metadata.id, metadata.version, manager, pinnedOnly)
        return bosca.comments.graphql.Comments(list, count.toInt())
    }

    /**
     * Whether this metadata has comments still awaiting a moderation decision
     * (pending or pending-approval) — a lightweight badge for content views, so
     * a moderator can spot which content needs attention. Surfaced only to
     * moderators (content MANAGE); everyone else always gets false.
     */
    @Field
    suspend fun hasUnmoderatedComments(authentication: AuthenticationContext?, metadata: Metadata): Boolean {
        if (!metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.MANAGE)) return false
        return commentService.hasUnmoderatedComments(metadata.id, metadata.version)
    }

    /** When the most recent comment on this metadata was posted, or null when it has none. */
    @Field
    suspend fun lastCommentAt(authentication: AuthenticationContext?, metadata: Metadata): OffsetDateTime? {
        if (!metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) return null
        return commentService.getLastCommentAt(metadata.id, metadata.version)
    }
}
