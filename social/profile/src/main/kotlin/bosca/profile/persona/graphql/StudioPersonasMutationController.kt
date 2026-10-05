package bosca.profile.persona.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.persona.model.StudioPersona
import bosca.profile.persona.model.StudioPersonaInput
import bosca.profile.persona.service.StudioPersonaService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

object StudioPersonasMutation

/** Mutation controller for Studio persona CRUD and profile assignment. */
@TypeController
class StudioPersonasMutationController(
    private val service: StudioPersonaService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<StudioPersonasMutation> {

    @Field
    suspend fun create(
        authentication: AuthenticationContext,
        input: StudioPersonaInput,
    ): StudioPersona {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.create(input)
    }

    @Field
    suspend fun update(
        authentication: AuthenticationContext,
        input: StudioPersonaInput,
    ): StudioPersona {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.update(input)
    }

    @Field
    suspend fun delete(
        authentication: AuthenticationContext,
        id: UUID,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        service.delete(id)
        return true
    }

    @Field
    suspend fun assignToProfile(
        authentication: AuthenticationContext,
        personaId: UUID,
        profileId: UUID,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        service.assignToProfile(personaId, profileId)
        return true
    }

    @Field
    suspend fun removeFromProfile(
        authentication: AuthenticationContext,
        personaId: UUID,
        profileId: UUID,
    ): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        service.removeFromProfile(personaId, profileId)
        return true
    }
}
