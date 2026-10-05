package bosca.trait.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.GroupEvaluator
import bosca.security.service.AuthenticationContext
import bosca.trait.model.Trait
import bosca.trait.model.TraitInput
import bosca.trait.service.TraitService

object TraitsMutation

@TypeController
class TraitMutationController(
    private val service: TraitService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<TraitsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, trait: TraitInput): Trait {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.add(trait)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, trait: TraitInput): Trait {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return service.edit(trait)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: String): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        service.delete(id)
        return true
    }
}
