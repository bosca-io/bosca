package bosca.atlas.server.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.search.graphql.Search
import bosca.search.model.SearchQuery
import bosca.search.model.SearchResult
import bosca.search.service.SearchService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

/** Profile owner search and the administrative indexing-preview namespace. */
@TypeController(type = "Search")
class OwnerSearchController(
    private val searchService: SearchService,
    private val profiles: ProfilePermissionEvaluator,
    private val groups: bosca.security.service.GroupEvaluator,
) : GraphQLController<Search> {
    @Field
    fun configuration(authentication: AuthenticationContext): bosca.search.graphql.SearchConfiguration {
        groups.verifyHasAdminGroup(authentication)
        return bosca.search.graphql.SearchConfiguration
    }

    @Field
    suspend fun search(authentication: AuthenticationContext?, query: SearchQuery): SearchResult {
        val result = searchService.search(query)
        val allowed =
            profiles
                .filterAllowed(
                    authentication,
                    result.documents.mapNotNull { it.profile },
                    PermissionAction.VIEW,
                )
                .mapTo(mutableSetOf()) { it.id }
        return result.copy(documents = result.documents.filter { it.profile?.id in allowed })
    }
}
