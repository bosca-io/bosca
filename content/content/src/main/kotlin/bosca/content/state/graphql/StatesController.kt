package bosca.content.state.graphql

import bosca.content.state.model.State
import bosca.content.state.service.StateService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

object WorkflowStates

@TypeController
class WorkflowStatesController(
    private val service: StateService,
    private val permissionEvaluator: GroupEvaluator
) : GraphQLController<WorkflowStates> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<State> {
        permissionEvaluator.verifyHasEditorGroup(authentication)
        return service.getAll()
    }

    @Field
    suspend fun state(authentication: AuthenticationContext, id: String): State? {
        permissionEvaluator.verifyHasEditorGroup(authentication)
        return service.get(id)
    }
}