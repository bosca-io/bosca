package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.security.service.SecurityConfiguration
import bosca.security.service.ThirdPartyType
import bosca.serialization.UUID

object Security

@TypeController
class SecurityController(
    private val securityService: SecurityService,
    private val securityConfiguration: SecurityConfiguration,
) : GraphQLController<Security> {

    @Field
    fun actions() = PermissionAction.entries.map { it.name }

    @Field
    fun apiTokens() = ApiTokens

    @Field
    fun groups() = Groups

    @Field
    fun passkeys() = Passkeys

    @Field
    fun thirdPartyProviders(): List<ThirdPartyType> = securityConfiguration.oauth2
        .asSequence()
        .filter { it.enabled }
        .mapNotNull { provider -> ThirdPartyType.entries.firstOrNull { it.name.equals(provider.type, ignoreCase = true) } }
        .distinct()
        .toList()

    @Field
    suspend fun principal(authentication: AuthenticationContext?): Principal {
        if (authentication == null) return Principal(id = UUID.NIL)
        val authenticated = authentication.authenticatedPrincipalOrNull()
        if (authenticated != null) return authenticated.asPrincipal()
        return securityService.getPrincipalById(UUID.NIL) ?: error("principal not found")
    }

    @Field
    fun principals() = Principals

    @Field
    fun admin() = AdminQuery
}
