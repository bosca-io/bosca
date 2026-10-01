package bosca.search.graphql

import bosca.content.security.CollectionPermissionEvaluator
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.security.OrganizationPermissionEvaluator
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.search.model.SearchDocument
import bosca.search.model.SearchQuery
import bosca.search.model.SearchResult
import bosca.search.service.SearchService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList

object Search

@TypeController
class SearchController(
    private val searchService: SearchService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val collectionPermissionEvaluator: CollectionPermissionEvaluator,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
    private val organizationPermissionEvaluator: OrganizationPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Search> {

    @Field
    suspend fun search(authentication: AuthenticationContext?, query: SearchQuery): SearchResult {
        val result = searchService.search(query)

        val allowedMetadata = metadataPermissionEvaluator
            .filterAllowed(authentication, result.documents.filter { it.metadata != null }.map { it.metadata ?: error("missing metadata") }, PermissionAction.VIEW)
            .mapTo(mutableSetOf()) { it.id }
        val allowedCollections = collectionPermissionEvaluator
            .filterAllowed(authentication, result.documents.filter { it.collection != null }.map { it.collection ?: error("missing collection") }, PermissionAction.VIEW)
            .mapTo(mutableSetOf()) { it.id }
        val allowedProfiles = profilePermissionEvaluator
            .filterAllowed(authentication, result.documents.filter { it.profile != null }.map { it.profile ?: error("missing profile") }, PermissionAction.VIEW)
            .mapTo(mutableSetOf()) { it.id }
        val allowedOrganizations = organizationPermissionEvaluator
            .filterAllowed(authentication, result.documents.filter { it.organization != null }.map { it.organization ?: error("missing organization") }, PermissionAction.VIEW)
            .mapTo(mutableSetOf()) { it.id }

        return result.copy(
            documents = result.documents.mapNotNull {
                SearchDocument(
                    metadata = it.metadata?.takeIf { allowedMetadata.contains(it.id) },
                    collection = it.collection?.takeIf { allowedCollections.contains(it.id) },
                    profile = it.profile?.takeIf { allowedProfiles.contains(it.id) },
                    organization = it.organization?.takeIf { allowedOrganizations.contains(it.id) }
                ).takeIf { it.metadata != null || it.collection != null || it.profile != null || it.organization != null }
            }.toList()
        )
    }

    @Field
    fun configuration(authentication: AuthenticationContext): SearchConfiguration {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return SearchConfiguration
    }
}
