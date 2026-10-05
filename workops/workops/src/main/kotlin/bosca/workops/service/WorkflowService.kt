package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.spec.Spec
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.WILDCARD_FROM_STATE
import bosca.workops.model.workflow.Workflow
import bosca.workops.model.workflow.WorkflowScheme
import bosca.workops.model.workflow.WorkflowState
import bosca.workops.model.workflow.WorkflowTransition
import bosca.workops.repository.WorkflowRepository
import bosca.workops.repository.WorkflowSchemeRepository

@ServiceImplementation
class WorkflowServiceImpl(
    private val workflowRepository: WorkflowRepository,
    private val schemeRepository: WorkflowSchemeRepository,
    private val projectService: ProjectService,
    private val workflowQueryRepository: WorkflowQueryRepository,
) : WorkflowService {

    override suspend fun listWorkflows(): List<Workflow> = workflowRepository.listWorkflows()

    override suspend fun getWorkflow(id: UUID): Workflow? = workflowRepository.getWorkflowById(id)

    override suspend fun listSchemes(): List<WorkflowScheme> = schemeRepository.listAll()

    override suspend fun getScheme(id: UUID): WorkflowScheme? = schemeRepository.getById(id)

    override suspend fun listStates(workflowId: UUID): List<WorkflowState> =
        workflowRepository.listStates(workflowId)

    override suspend fun getState(id: UUID): WorkflowState? =
        workflowRepository.getStateById(id)

    override suspend fun listTransitions(workflowId: UUID): List<WorkflowTransition> =
        workflowRepository.listTransitions(workflowId)

    override suspend fun countUnresolvedSubtasks(taskId: UUID): Int =
        workflowQueryRepository.countUnresolvedSubtasks(taskId).toInt()

    override suspend fun resolveWorkflowForTask(task: Task): WorkflowResolution {
        val project = projectService.getById(task.projectId)
            ?: throw WorkOpsNotFoundException("Project", task.projectId.toString())
        val schemeId = project.defaultWorkflowSchemeId
            ?: throw WorkOpsNotFoundException("WorkflowScheme", "project ${project.key}")
        val scheme = schemeRepository.getById(schemeId)
            ?: throw WorkOpsNotFoundException("WorkflowScheme", schemeId.toString())
        val workflowId = perTaskTypeOverride(scheme, task.taskTypeId) ?: scheme.defaultWorkflowId
        val workflow = workflowRepository.getWorkflowById(workflowId)
            ?: throw WorkOpsNotFoundException("Workflow", workflowId.toString())
        val currentState = workflowRepository.getStateByStatus(workflow.id, task.statusId)
            ?: throw WorkOpsNotFoundException(
                "WorkflowState",
                "workflow=${workflow.id}, status=${task.statusId}",
            )
        val transitions = workflowRepository.listTransitions(workflow.id)
        return WorkflowResolution(workflow, currentState, transitions)
    }

    override suspend fun resolveWorkflowForSpec(spec: Spec): WorkflowResolution {
        val workflow = workflowRepository.getWorkflowById(spec.workflowId)
            ?: throw WorkOpsNotFoundException("Workflow", spec.workflowId.toString())
        val currentState = workflowRepository.getStateByStatus(workflow.id, spec.statusId)
            ?: throw WorkOpsNotFoundException(
                "WorkflowState",
                "workflow=${workflow.id}, status=${spec.statusId}",
            )
        val transitions = workflowRepository.listTransitions(workflow.id)
        return WorkflowResolution(workflow, currentState, transitions)
    }

    /**
     * Decode the per-task-type override map's [WorkflowScheme.perTaskTypeWorkflowIds]
     * jsonb blob lazily to find the workflow id for [taskTypeId], if any.
     * Stored as an object keyed by stringified UUIDs because Bosca's
     * repository binder lacks a `Map<UUID, UUID>` mapper; see the field's
     * KDoc on [WorkflowScheme].
     */
    private fun perTaskTypeOverride(scheme: WorkflowScheme, taskTypeId: UUID): UUID? {
        val node = scheme.perTaskTypeWorkflowIds as? kotlinx.serialization.json.JsonObject
            ?: return null
        val raw = node[taskTypeId.toString()] as? kotlinx.serialization.json.JsonPrimitive
            ?: return null
        return runCatching { UUID.parse(raw.content) }.getOrNull()
    }
}

/**
 * Filter [WorkflowTransition]s to those whose `fromStateIds` accept
 * the given [currentStateId] — either via wildcard or by listing the
 * id explicitly. Used both by the engine before calling the
 * evaluator and by the GraphQL surface to render available actions.
 */
fun List<WorkflowTransition>.matching(currentStateId: UUID): List<WorkflowTransition> = filter { transition ->
    transition.fromStateIds.any { it == WILDCARD_FROM_STATE } ||
        transition.fromStateIds.any { it == currentStateId.toString() }
}
