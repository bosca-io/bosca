package bosca.graphql.persistedqueries

import bosca.graphql.*
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext

@TypeController
class PersistedQueriesController(
    private val repository: PersistedQueryRepository
) : GraphQLController<PersistedQueries> {

    @Field
    suspend fun query(
        authenticationContext: AuthenticationContext,
        sha256: String
    ): PersistedQuery? {
        if (authenticationContext.principal()?.hasGroup("sa")?.takeIf { it } == null) {
            authenticationContext.principal()?.hasGroup("administrators")?.takeIf { it } ?: return null
        }
        return repository.findBySha256(sha256).firstOrNull()
    }
}
