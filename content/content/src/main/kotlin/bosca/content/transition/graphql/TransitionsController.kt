package bosca.content.transition.graphql

import bosca.content.transition.model.Transition
import bosca.content.transition.service.TransitionService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

object Transitions

@TypeController
class TransitionsController(
    private val service: TransitionService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Transitions> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Transition> {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return service.getAll()
    }

    @Field
    suspend fun transition(authentication: AuthenticationContext, fromStateId: String, toStateId: String): Transition? {
        groupEvaluator.verifyHasEditorGroup(authentication)
        return service.get(fromStateId, toStateId)
    }
}
