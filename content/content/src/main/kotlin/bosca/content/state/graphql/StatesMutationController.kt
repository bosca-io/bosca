package bosca.content.state.graphql

import bosca.content.state.model.StateInput
import bosca.content.state.service.StateService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.content.state.model.State

object WorkflowStatesMutation

@TypeController
class WorkflowStatesMutationController(
    private val service: StateService,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<WorkflowStatesMutation> {

    @Field
    suspend fun add(authenticationContext: AuthenticationContext, state: StateInput): State {
        groupEvaluator.verifyHasManagerGroup(authenticationContext)
        return service.add(state)
    }

    @Field
    suspend fun edit(authenticationContext: AuthenticationContext, state: StateInput): State {
        groupEvaluator.verifyHasManagerGroup(authenticationContext)
        return service.edit(state.id, state)
    }

    @Field
    suspend fun delete(authenticationContext: AuthenticationContext, id: String): Boolean {
        groupEvaluator.verifyHasManagerGroup(authenticationContext)
        service.delete(id)
        return true
    }
}