package bosca.graphql.persistedqueries

import bosca.di.ObjectProvider
import bosca.graphql.GraphQLController
import bosca.graphql.GraphQLService
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext

@TypeController
class PersistedQueriesMutationController(
    private val repository: PersistedQueryRepository,
    private val graphQlService: ObjectProvider<GraphQLService>
) : GraphQLController<PersistedQueriesMutation> {

    private fun verifySaOrAdmin(authenticationContext: AuthenticationContext) {
        val principal = authenticationContext.principal()
            ?: throw SecurityException("Unauthorized: authentication required")
        if (!principal.hasGroup("sa") && !principal.hasGroup("administrators")) {
            throw SecurityException("Unauthorized: requires sa or administrators group")
        }
    }

    @Field
    suspend fun addAll(
        authenticationContext: AuthenticationContext,
        application: String,
        queries: List<PersistedQuery>
    ): Boolean {
        verifySaOrAdmin(authenticationContext)
        queries.forEach { repository.upsert(application, it.query, it.sha256) }
        graphQlService.get().clearPersistedQueries()
        return true
    }

    @Field
    suspend fun add(
        authenticationContext: AuthenticationContext,
        application: String,
        query: String,
        sha256: String
    ): Boolean {
        verifySaOrAdmin(authenticationContext)
        repository.upsert(application, query, sha256)
        graphQlService.get().clearPersistedQueries()
        return true
    }

    @Field
    suspend fun delete(
        authenticationContext: AuthenticationContext,
        application: String,
        sha256: String
    ): Boolean {
        verifySaOrAdmin(authenticationContext)
        val valid = repository.delete(application, sha256) > 0
        if (valid) graphQlService.get().clearPersistedQueries()
        return valid
    }
}
