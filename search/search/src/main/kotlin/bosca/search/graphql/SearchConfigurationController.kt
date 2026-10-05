package bosca.search.graphql

import bosca.category.service.CategoryService
import bosca.content.collection.service.CollectionService
import bosca.content.collection.service.getCategories
import bosca.content.metadata.service.MetadataService
import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.transformations.CollectionToSearchDocument
import bosca.content.transformations.MetadataToSearchDocument
import bosca.content.transformations.ProfileToSearchDocument
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.search.IndexStorageSystem
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
object SearchConfiguration

@TypeController
class SearchConfigurationController(
    private val metadataService: MetadataService,
    private val collectionService: CollectionService,
    private val profileService: ProfileService,
    private val categoryService: CategoryService,
    private val metadataToSearch: MetadataToSearchDocument,
    private val collectionToSearch: CollectionToSearchDocument,
    private val profileToSearch: ProfileToSearchDocument,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
    private val json: Json,
) : GraphQLController<SearchConfiguration> {

    @Field
    suspend fun previewMetadata(authentication: AuthenticationContext, id: UUID): JsonElement? {
        val metadata = metadataService.getById(id) ?: return null
        metadataPermissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
        return json.encodeToJsonElement(metadataToSearch.toContext(IndexStorageSystem(name = "Admin Search Index"), metadata))
    }

    @Field
    suspend fun previewCollection(authentication: AuthenticationContext, id: UUID): JsonElement? {
        val collection = collectionService.getById(id) ?: return null
        collectionPermissionEvaluator.verifyAllowed(authentication, collection, PermissionAction.VIEW)
        val categories = collectionService.getCategories(id, categoryService)
        return json.encodeToJsonElement(collectionToSearch.toContext(IndexStorageSystem(name = "Admin Search Index"), collection, categories))
    }

    @Field
    suspend fun previewProfile(authentication: AuthenticationContext, id: UUID): JsonElement? {
        val profile = profileService.getById(id)
        profilePermissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.VIEW)
        return json.encodeToJsonElement(profileToSearch.toContext(IndexStorageSystem(name = "Admin Search Index"), profile))
    }
}
