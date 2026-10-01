package bosca.profile.profile.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.attribute.graphql.ProfileAttributeTypes
import bosca.profile.model.Profile
import bosca.profile.persona.graphql.StudioPersonas
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object Profiles

@TypeController
class ProfilesController(
    private val profilesService: ProfileService,
    private val permissionEvaluator: ProfilePermissionEvaluator,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Profiles> {

    @Field
    suspend fun all(
        authentication: AuthenticationContext,
        limit: Int,
        offset: Long
    ): List<Profile> {
        groupEvaluator.verifyHasSaGroup(authentication)
        return profilesService.getAll(offset, limit)
    }

    /**
     * Returns the profiles whose linked principal is a member of the named security group.
     *
     * Authorization: the caller must themselves be a member of [name] (or an administrator).
     * This stops the query from being a general-purpose user enumeration endpoint while
     * letting members of, e.g., the `messaging` group resolve the @mention reachability
     * roster for their group.
     */
    @Field
    suspend fun byGroup(
        authentication: AuthenticationContext,
        name: String,
        limit: Int,
        offset: Long,
    ): List<Profile> {
        if (!groupEvaluator.hasGroup(authentication, name)) {
            // Fall back to messaging-specific check so members of `messaging` (the most
            // common caller for this field) don't have to be in the literal queried group.
            if (name != "messaging" || !groupEvaluator.hasMessagingGroup(authentication)) {
                throw SecurityException("Not authorized to enumerate group '$name'")
            }
        }
        return profilesService.getByGroupName(name, offset, limit)
    }

    @Field
    suspend fun current(authentication: AuthenticationContext?): List<Profile> {
        val principal = authentication?.principal() ?: return listOf(
            Profile(
                id = UUID.NIL,
                name = "Anonymous",
                visibility = ProfileVisibility.PUBLIC,
                type = ProfileType.GENERIC
            )
        )
        val profiles = profilesService.getByPrincipal(principal.id).filterNot(Profile::isDeleted)
        val primaryProfileId = principal.asPrincipal().primaryProfileId ?: return profiles
        return profiles.sortedBy { if (it.id == primaryProfileId) 0 else 1 }
    }

    @Field
    suspend fun profile(authentication: AuthenticationContext, id: UUID): Profile {
        val profile = profilesService.getById(id)
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.VIEW)
        return profile
    }

    @Field
    fun attributeTypes() = ProfileAttributeTypes

    @Field
    fun studioPersonas() = StudioPersonas

}
