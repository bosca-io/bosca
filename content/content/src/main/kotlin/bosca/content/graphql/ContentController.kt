package bosca.content.graphql

import bosca.category.graphql.Categories
import bosca.comments.service.CommentService
import bosca.content.collection.graphql.Collections
import bosca.content.healthcheck.ContentHealthCheck
import bosca.content.collection.graphql.CollectionSupplementaryContext
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.model.ContentItem
import bosca.content.collection.service.CollectionService
import bosca.content.find.FindQueryInput
import bosca.content.guide.graphql.Guides
import bosca.content.metadata.graphql.*
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.state.graphql.WorkflowStates
import bosca.content.tools.graphql.TemplateAttributeTools
import bosca.content.transition.graphql.Transitions
import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.slug.service.SlugService
import bosca.source.graphql.Sources

object Content

@TypeController
class ContentController(
    private val slugService: SlugService,
    private val metadataServiceProvider: ObjectProvider<MetadataService>,
    private val metadataPermissionEvaluatorProvider: ObjectProvider<MetadataPermissionEvaluator>,
    private val collectionServiceProvider: ObjectProvider<CollectionService>,
    private val collectionPermissionEvaluatorProvider: ObjectProvider<CollectionPermissionEvaluator>,
    private val profileServiceProvider: ObjectProvider<ProfileService>,
    private val profilePermissionEvaluatorProvider: ObjectProvider<ProfilePermissionEvaluator>,
    private val commentServiceProvider: ObjectProvider<CommentService>,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Content> {

    @Field
    fun collections() = Collections

    @Field
    fun categories() = Categories

    @Field
    suspend fun checkCollectionActions(
        authentication: AuthenticationContext?,
        actions: List<PermissionAction>,
        ids: List<UUID>
    ): List<UUID> {
        val passed = mutableListOf<UUID>()
        val evaluator = collectionPermissionEvaluatorProvider.get()
        val service = collectionServiceProvider.get()
        for (id in ids) {
            val collection = service.getById(id) ?: continue
            for (action in actions) {
                if (evaluator.isAllowed(authentication, collection, action)) {
                    passed.add(id)
                }
            }
        }
        return passed
    }

    @Field
    suspend fun checkMetadataActions(
        authentication: AuthenticationContext?,
        actions: List<PermissionAction>,
        ids: List<UUID>
    ): List<UUID> {
        val passed = mutableListOf<UUID>()
        val evaluator = metadataPermissionEvaluatorProvider.get()
        val service = metadataServiceProvider.get()
        for (id in ids) {
            val collection = service.getById(id) ?: continue
            for (action in actions) {
                if (evaluator.isAllowed(authentication, collection, action)) {
                    passed.add(id)
                }
            }
        }
        return passed
    }

    @Field
    suspend fun slugAvailable(
        authentication: AuthenticationContext,
        slug: String
    ): Boolean = transaction {
        groupEvaluator.verifyHasEditorGroup(authentication)
        val s = slugService.get(slug)
        s == null
    }

    @Field
    fun sources() = Sources

    @Field
    suspend fun slug(authentication: AuthenticationContext?, slug: String): ContentItem? {
        val slug = slugService.get(slug) ?: return null
        slug.metadataId?.let {
            val metadataService = metadataServiceProvider.get()
            val metadataPermissionEvaluator = metadataPermissionEvaluatorProvider.get()
            val metadata = metadataService.getById(it) ?: return null
            if (metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) {
                return metadata
            }
        }
        slug.collectionId?.let { id ->
            val collectionService = collectionServiceProvider.get()
            val collectionPermissionEvaluator = collectionPermissionEvaluatorProvider.get()
            val collection = slug.languageTag?.let {
                collectionService.getLanguageVariant(id, it)
            } ?: collectionService.getById(id) ?: return null
            if (collectionPermissionEvaluator.isAllowed(authentication, collection, PermissionAction.VIEW)) {
                if (collection is CollectionLanguageVariant) {
                    val c = collectionService.getById(id) ?: return null
                    c.defaultLanguageVariant = collection
                    return c
                }
                return collection
            }
        }
        slug.profileId?.let {
            val profileService = profileServiceProvider.get()
            val profilePermissionEvaluator = profilePermissionEvaluatorProvider.get()
            val profile = profileService.getById(it)
            if (profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) {
                return profile
            }
        }
        return null
    }

    @Field
    suspend fun collection(authentication: AuthenticationContext?, id: UUID): Collection? {
        val service = collectionServiceProvider.get()
        val evaluator = collectionPermissionEvaluatorProvider.get()
        val collection = service.getById(id) ?: return null
        if (!evaluator.isAllowed(authentication, collection, PermissionAction.VIEW)) return null
        return collection
    }

    @Field
    suspend fun collectionSupplementary(
        authentication: AuthenticationContext?,
        supplementaryId: UUID
    ): CollectionSupplementaryContext? {
        val service = collectionServiceProvider.get()
        val evaluator = collectionPermissionEvaluatorProvider.get()
        val supplementary = service.getSupplementaryById(supplementaryId) ?: return null
        val collection = service.getById(supplementary.collectionId) ?: return null
        if (!evaluator.isSupplementaryAllowed(authentication, collection, PermissionAction.VIEW)) return null
        return CollectionSupplementaryContext(
            collection = collection,
            supplementary = supplementary
        )
    }

    @Field
    fun collectionTemplates() = CollectionTemplates

    @Field
    suspend fun findCollectionsCount(query: FindQueryInput): Long {
        val service = collectionServiceProvider.get()
        return service.findCount(query)
    }

    @Field
    suspend fun findCollections(authentication: AuthenticationContext?, query: FindQueryInput): List<Collection> {
        val service = collectionServiceProvider.get()
        val evaluator = collectionPermissionEvaluatorProvider.get()
        return evaluator.filterAllowed(authentication, service.find(query), PermissionAction.VIEW).map { it as Collection }
    }

    @Field
    suspend fun findCollectionsBySystem(authentication: AuthenticationContext?, query: FindQueryInput): List<Collection> {
        val service = collectionServiceProvider.get()
        val evaluator = collectionPermissionEvaluatorProvider.get()
        return evaluator.filterAllowed(authentication, service.findBySystem(query), PermissionAction.VIEW).map { it as Collection }
    }

    @Field
    suspend fun findMetadataCount(query: FindQueryInput): Long {
        val service = metadataServiceProvider.get()
        return service.findCount(query)
    }

    @Field
    suspend fun findMetadata(authentication: AuthenticationContext?, query: FindQueryInput): List<Metadata> {
        val service = metadataServiceProvider.get()
        val evaluator = metadataPermissionEvaluatorProvider.get()
        return evaluator.filterAllowed(authentication, service.find(query), PermissionAction.VIEW)
    }

    @Field
    suspend fun findMetadataBySystem(authentication: AuthenticationContext?, query: FindQueryInput): List<Metadata> {
        val service = metadataServiceProvider.get()
        val evaluator = metadataPermissionEvaluatorProvider.get()
        return evaluator.filterAllowed(authentication, service.findBySystem(query), PermissionAction.VIEW)
    }

    @Field
    suspend fun metadata(authenticationContext: AuthenticationContext?, id: UUID, version: Int?): Metadata? {
        val service = metadataServiceProvider.get()
        val evaluator = metadataPermissionEvaluatorProvider.get()
        val metadata = service.getById(id, version) ?: return null
        if (!evaluator.isAllowed(authenticationContext, metadata, PermissionAction.VIEW)) return null
        return metadata
    }

    /** Metadata items that have comments, most-recent-comment first — the moderation landing list. */
    @Field
    suspend fun commentedMetadata(authenticationContext: AuthenticationContext?, offset: Int, limit: Int): List<Metadata> {
        val service = metadataServiceProvider.get()
        val evaluator = metadataPermissionEvaluatorProvider.get()
        return commentServiceProvider.get().getCommentedMetadata(offset.toLong(), limit.toLong()).mapNotNull { ref ->
            val metadata = service.getById(ref.metadataId, ref.version) ?: return@mapNotNull null
            if (evaluator.isAllowed(authenticationContext, metadata, PermissionAction.VIEW)) metadata else null
        }
    }

    @Field
    suspend fun metadataSupplementary(authenticationContext: AuthenticationContext?, supplementaryId: UUID): MetadataSupplementaryContext? {
        val service = metadataServiceProvider.get()
        val evaluator = metadataPermissionEvaluatorProvider.get()
        val supplementary = service.getSupplementaryById(supplementaryId) ?: return null
        val metadata = service.getById(supplementary.metadataId) ?: return null
        if (!evaluator.isSupplementaryAllowed(authenticationContext, metadata, PermissionAction.VIEW)) return null
        return MetadataSupplementaryContext(
            metadata = metadata,
            supplementary = supplementary
        )
    }

    @Field
    fun documentTemplates() = DocumentTemplates

    @Field
    fun dataTemplates() = DataTemplates

    @Field
    fun guideTemplates() = GuideTemplates

    @Field
    fun guides() = Guides

    @Field
    fun states() = WorkflowStates

    @Field
    fun transitions() = Transitions

    @Field
    fun templateAttributeTools() = TemplateAttributeTools

    @Field
    fun healthCheck() = ContentHealthCheck
}
