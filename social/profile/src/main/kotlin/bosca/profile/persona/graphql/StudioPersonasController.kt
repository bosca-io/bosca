package bosca.profile.persona.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.persona.model.StudioPersona
import bosca.profile.persona.service.StudioPersonaService
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityException
import bosca.serialization.UUID

object StudioPersonas

/** Query controller for Studio persona operations. */
@TypeController
class StudioPersonasController(
    private val service: StudioPersonaService,
    private val profileService: ProfileService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<StudioPersonas> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<StudioPersona> {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.getAll()
    }

    @Field
    suspend fun persona(authentication: AuthenticationContext, id: UUID): StudioPersona? {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.get(id)
    }

    @Field
    suspend fun byProfile(authentication: AuthenticationContext, profileId: UUID): List<StudioPersona> {
        if (!groupEvaluator.hasAdminGroup(authentication)) {
            val principal = authentication.principal() ?: throw SecurityException("not authenticated")
            val ownedIds = profileService.getByPrincipal(principal.id).map { it.id }.toSet()
            if (profileId !in ownedIds) throw SecurityException("not authorized")
        }
        return service.getByProfile(profileId)
    }
}
