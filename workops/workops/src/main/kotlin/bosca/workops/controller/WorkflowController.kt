package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.WILDCARD_FROM_STATE
import bosca.workops.model.workflow.Workflow
import bosca.workops.model.workflow.WorkflowScheme
import bosca.workops.model.workflow.WorkflowState
import bosca.workops.model.workflow.WorkflowTransition
import bosca.workops.service.StatusService
import bosca.workops.service.WorkflowService
import kotlinx.serialization.json.JsonElement

@TypeController(type = "WorkOpsStatus")
class StatusFieldsController : GraphQLController<Status> {
    @Field fun id(s: Status) = s.id
    @Field fun name(s: Status) = s.name
    @Field fun description(s: Status) = s.description
    @Field fun category(s: Status) = s.category
    @Field fun colorHex(s: Status) = s.colorHex
    @Field fun version(s: Status) = s.version
}

@TypeController(type = "WorkOpsWorkflow")
class WorkflowTypeController(
    private val service: WorkflowService,
) : GraphQLController<Workflow> {

    @Field fun id(w: Workflow) = w.id
    @Field fun name(w: Workflow) = w.name
    @Field fun description(w: Workflow) = w.description
    @Field fun initialStateId(w: Workflow) = w.initialStateId
    @Field fun version(w: Workflow) = w.version

    @Field
    suspend fun states(workflow: Workflow): List<WorkflowState> = service.listStates(workflow.id)

    @Field
    suspend fun transitions(workflow: Workflow): List<WorkflowTransition> =
        service.listTransitions(workflow.id)
}

@TypeController(type = "WorkOpsWorkflowState")
class WorkflowStateTypeController(
    private val statusService: StatusService,
) : GraphQLController<WorkflowState> {

    @Field fun id(s: WorkflowState) = s.id
    @Field fun workflowId(s: WorkflowState) = s.workflowId
    @Field fun statusId(s: WorkflowState) = s.statusId
    @Field fun displayOrder(s: WorkflowState) = s.displayOrder
    @Field fun slaPolicyId(s: WorkflowState) = s.slaPolicyId

    @Field
    suspend fun status(state: WorkflowState): Status =
        statusService.getById(state.statusId)
            ?: error("Status ${state.statusId} missing for workflow_state ${state.id}")

    @Field
    suspend fun name(state: WorkflowState): String =
        (statusService.getById(state.statusId)
            ?: error("Status ${state.statusId} missing for workflow_state ${state.id}")).name
}

@TypeController(type = "WorkOpsWorkflowTransition")
class WorkflowTransitionTypeController(
    private val workflowService: WorkflowService,
) : GraphQLController<WorkflowTransition> {
    @Field fun id(t: WorkflowTransition) = t.id
    @Field fun workflowId(t: WorkflowTransition) = t.workflowId
    @Field fun name(t: WorkflowTransition) = t.name
    @Field fun description(t: WorkflowTransition) = t.description
    @Field fun fromStateIds(t: WorkflowTransition) = t.fromStateIds

    @Field
    suspend fun fromStates(t: WorkflowTransition): List<WorkflowState> {
        val states = workflowService.listStates(t.workflowId)
        return t.fromStateIds
            .filter { it != WILDCARD_FROM_STATE }
            .mapNotNull { id -> states.find { it.id.toString() == id } }
    }

    @Field
    suspend fun fromState(t: WorkflowTransition): WorkflowState? {
        val firstId = t.fromStateIds.firstOrNull { it != WILDCARD_FROM_STATE } ?: return null
        val states = workflowService.listStates(t.workflowId)
        return states.find { it.id.toString() == firstId }
    }

    @Field fun toStateId(t: WorkflowTransition) = t.toStateId
    @Field suspend fun toState(t: WorkflowTransition): WorkflowState =
        workflowService.getState(t.toStateId)
            ?: error("WorkflowState ${t.toStateId} missing for transition ${t.id}")
    @Field fun conditions(t: WorkflowTransition): JsonElement = t.conditions
    @Field fun validators(t: WorkflowTransition): JsonElement = t.validators
    @Field fun postFunctions(t: WorkflowTransition): JsonElement = t.postFunctions
    @Field fun screenId(t: WorkflowTransition) = t.screenId
}

@TypeController(type = "WorkOpsWorkflowScheme")
class WorkflowSchemeTypeController : GraphQLController<WorkflowScheme> {
    @Field fun id(s: WorkflowScheme) = s.id
    @Field fun name(s: WorkflowScheme) = s.name
    @Field fun description(s: WorkflowScheme) = s.description
    @Field fun defaultWorkflowId(s: WorkflowScheme) = s.defaultWorkflowId
    @Field fun perTaskTypeWorkflowIds(s: WorkflowScheme): JsonElement = s.perTaskTypeWorkflowIds
    @Field fun version(s: WorkflowScheme) = s.version
}

object WorkOpsStatuses

@TypeController
class StatusQueryController(
    private val service: StatusService,
) : GraphQLController<WorkOpsStatuses> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Status> {
        return service.list()
    }

    @Field
    suspend fun status(authentication: AuthenticationContext, id: UUID): Status? {
        return service.getById(id)
    }
}

object WorkOpsWorkflows

@TypeController
class WorkflowQueryController(
    private val service: WorkflowService,
) : GraphQLController<WorkOpsWorkflows> {

    @Field
    suspend fun all(authentication: AuthenticationContext): List<Workflow> {
        return service.listWorkflows()
    }

    @Field
    suspend fun workflow(authentication: AuthenticationContext, id: UUID): Workflow? {
        return service.getWorkflow(id)
    }

    @Field
    suspend fun schemes(authentication: AuthenticationContext): List<WorkflowScheme> {
        return service.listSchemes()
    }

    @Field
    suspend fun scheme(authentication: AuthenticationContext, id: UUID): WorkflowScheme? {
        return service.getScheme(id)
    }
}
