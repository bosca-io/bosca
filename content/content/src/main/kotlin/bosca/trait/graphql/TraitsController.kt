package bosca.trait.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.trait.model.Trait
import bosca.trait.service.TraitService

object Traits

@TypeController
class TraitsController(
    private val service: TraitService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Traits> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Trait> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return service.getAll()
    }
}
