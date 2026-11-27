package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.workflow.CreateWorkflowInput
import bosca.workops.model.workflow.CreateWorkflowSchemeInput
import bosca.workops.model.workflow.UpdateWorkflowInput
import bosca.workops.model.workflow.UpdateWorkflowSchemeInput
import bosca.workops.service.WorkflowService

@TypeController
class WorkflowMutationController(
    private val workflowService: WorkflowService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<WorkOpsWorkflowsMutation> {

    @Field
    suspend fun create(authentication: AuthenticationContext, input: CreateWorkflowInput) =
        authorized(authentication) { workflowService.createWorkflow(input) }

    @Field
    suspend fun update(authentication: AuthenticationContext, id: UUID, input: UpdateWorkflowInput) =
        authorized(authentication) { workflowService.updateWorkflow(id, input) }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean = authorized(authentication) {
        workflowService.deleteWorkflow(id)
        true
    }

    @Field
    suspend fun createScheme(authentication: AuthenticationContext, input: CreateWorkflowSchemeInput) =
        authorized(authentication) { workflowService.createScheme(input) }

    @Field
    suspend fun updateScheme(authentication: AuthenticationContext, id: UUID, input: UpdateWorkflowSchemeInput) =
        authorized(authentication) { workflowService.updateScheme(id, input) }

    @Field
    suspend fun deleteScheme(authentication: AuthenticationContext, id: UUID): Boolean = authorized(authentication) {
        workflowService.deleteScheme(id)
        true
    }

    private suspend fun <T> authorized(authentication: AuthenticationContext, action: suspend () -> T): T {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return action()
    }
}
