package bosca.security.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.service.GroupEvaluator
import bosca.security.model.Group
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.model.PrincipalLogin
import bosca.security.model.PrincipalCredentialAndType
import bosca.security.model.OAuth2CredentialAttributes
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import kotlinx.serialization.json.JsonElement


@TypeController
class PrincipalController(
    private val securityService: SecurityService,
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Principal> {

    @Field
    fun id(principal: Principal) = principal.id

    @Field
    fun verified(principal: Principal) = principal.verified

    @Field
    fun anonymous(principal: Principal) = principal.anonymous

    @Field
    fun created(principal: Principal) = principal.created

    @Field
    fun modified(principal: Principal) = principal.modified

    @Field
    fun deletedAt(principal: Principal) = principal.deletedAt

    @Field
    fun primaryProfileId(principal: Principal) = principal.primaryProfileId

    @Field
    fun attributes(authentication: AuthenticationContext?, principal: Principal): JsonElement? {
        if (!canReadPrivateFields(authentication, principal)) {
            return null
        }
        return principal.attributes
    }

    @Field
    suspend fun profiles(authenticatedPrincipal: AuthenticationContext?, principal: Principal): List<Profile> {
        val profiles = profileService.getByPrincipal(principal.id)
        return profilePermissionEvaluator.filterAllowed(authenticatedPrincipal, profiles, PermissionAction.VIEW)
    }

    @Field
    suspend fun credentials(authentication: AuthenticationContext?, principal: Principal): List<PrincipalCredentialAndType> {
        if (!canReadPrivateFields(authentication, principal)) {
            return emptyList()
        }
        return securityService.getCredentials(principal).map {
            PrincipalCredentialAndType(
                identifier = it.attributes.identifier,
                type = it.type,
                provider = (it.attributes as? OAuth2CredentialAttributes)?.source,
                originator = it.originator,
                lastOriginator = it.lastOriginator,
            )
        }
    }

    @Field
    suspend fun groups(authentication: AuthenticationContext?, principal: Principal): List<Group> {
        if (!canReadPrivateFields(authentication, principal)) {
            return emptyList()
        }
        return securityService.getPrincipalGroups(principal.id)
    }

    @Field
    suspend fun lastLogin(authentication: AuthenticationContext?, principal: Principal): OffsetDateTime? {
        val authenticatedPrincipal = authentication?.principal()
        if (authenticatedPrincipal?.id == principal.id || groupEvaluator.hasSaGroup(authentication)) {
            return securityService.getPrincipalLastLogin(principal.id)
        }
        return null
    }

    @Field
    suspend fun loginHistory(
        authentication: AuthenticationContext?,
        principal: Principal,
        offset: Long,
        limit: Int,
    ): List<PrincipalLogin> {
        if (!canReadPrivateFields(authentication, principal)) return emptyList()
        require(offset >= 0) { "offset must not be negative" }
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return securityService.getPrincipalLogins(principal.id, offset, limit)
    }

    private fun canReadPrivateFields(authentication: AuthenticationContext?, principal: Principal): Boolean =
        authentication.authenticatedPrincipalOrNull()?.id == principal.id || groupEvaluator.hasSaGroup(authentication)
}
