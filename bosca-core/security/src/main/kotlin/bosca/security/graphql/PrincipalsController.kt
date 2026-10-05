package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID

object Principals

@TypeController
class PrincipalsController(
    private val securityService: SecurityService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Principals> {

    @Field
    suspend fun all(
        authentication: AuthenticationContext,
        limit: Int,
        offset: Long,
        includeDeleted: Boolean = false
    ): List<Principal> {
        if (!groupEvaluator.hasAdminGroup(authentication)) {
            groupEvaluator.throwUnauthorized()
        }
        return securityService.getPrincipals(offset, limit, includeDeleted)
    }

    @Field
    suspend fun current(authentication: AuthenticationContext?): Principal {
        val authenticated = authentication.authenticatedPrincipalOrNull()
        if (authenticated != null) return authenticated.asPrincipal()
        return securityService.getPrincipalById(UUID.NIL) ?: error("principal not found")
    }

    @Field
    suspend fun principal(authentication: AuthenticationContext, id: UUID): Principal? {
        if (!groupEvaluator.hasAdminGroup(authentication)) {
            groupEvaluator.throwUnauthorized()
        }
        return securityService.getPrincipalById(id)
    }
}
